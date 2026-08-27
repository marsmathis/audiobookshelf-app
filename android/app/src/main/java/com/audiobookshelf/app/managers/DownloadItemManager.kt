package com.audiobookshelf.app.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.fullName
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.JSArray
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manages the process-owned queue for app-managed downloads. */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val context: Context,
        private var clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val connectivityManager =
          context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private var lastActiveNetwork: Network? = connectivityManager.activeNetwork
  private val activeCalls = ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>()
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val scanLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private val finalizingItems = mutableSetOf<String>()
  private val refreshingServerIds = mutableSetOf<String>()
  private val apiHandler = ApiHandler(context)
  private val activePartUrls = ConcurrentHashMap<String, String>()
  private val activeTransferTokens = ConcurrentHashMap<String, Any>()
  private val speedSamples = mutableMapOf<String, SpeedSample>()
  private var watcherRunning = false
  private val jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
    private set
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()
    private set

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onQueueChanged(hasWork: Boolean)
  }

  interface InternalProgressCallback {
    fun onSizeResolved(totalBytes: Long)
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
    fun onAuthError()
  }

  init {
    try {
      IncompleteDownloadCleanup.cleanupExpired(context)
    } catch (e: Exception) {
      AbsLogger.error(tag, "Failed to clean expired incomplete downloads: ${e.message}")
    }
  }

  @Synchronized
  fun setEventEmitter(eventEmitter: DownloadEventEmitter) {
    clientEventEmitter = eventEmitter
    downloadItemQueue.forEach(clientEventEmitter::onDownloadItem)
    notifyQueueChanged()
  }

  @Synchronized
  fun restoreQueue() {
    if (downloadItemQueue.isNotEmpty()) return
    val items =
            try {
              DeviceManager.dbManager.getDownloadItems()
            } catch (e: Exception) {
              AbsLogger.error(tag, "Failed to read restored download queue: ${e.message}")
              emptyList()
            }
    items.sortedBy { it.queuePosition }.forEach { item ->
      try {
        if (!hasValidDownloadPaths(item)) {
          AbsLogger.error(tag, "Removing malformed restored download item ${item.id}")
          DeviceManager.dbManager.removeDownloadItem(item.id)
          IncompleteDownloadCleanup.cancel(context, item.id)
          return@forEach
        }
        item.downloadItemParts.filter { it.moved }.forEach { part ->
          if (!finalizedFileExists(part)) {
            AbsLogger.error(tag, "Finalized file is missing; resetting ${part.filename}")
            part.moved = false
            part.completed = false
            part.completedDestinationUri = null
            part.downloadId = null
            part.reusedExistingFile = false
          }
        }
        if (item.isDownloadFinished) {
          downloadItemQueue.add(item)
          checkDownloadItemFinished(item)
          return@forEach
        }
        var resetFailed = false
        item.downloadItemParts.forEach { part ->
          if (part.moved) return@forEach
          if (item.terminalFailureAt != null && part.failed) return@forEach
          if (!resetPartForFreshDownload(part)) resetFailed = true
          part.waitingForSpace = false
          part.waitingForNetwork = false
          part.waitingForWifi = false
          part.waitingForRetry = false
          part.retryAfterTime = null
          part.lastError = null
          part.bytesPerSecond = 0L
        }
        if (resetFailed) {
          item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
          item.stagingCleanupAt = null
        }
        if (item.terminalFailureAt != null) {
          item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
        }
        downloadItemQueue.add(item)
        if (item.terminalFailureAt != null) IncompleteDownloadCleanup.schedule(context, item)
        clientEventEmitter.onDownloadItem(item)
      } catch (e: Exception) {
        AbsLogger.error(tag, "Failed to restore download item ${item.id}: ${e.message}")
      }
    }
    normalizeQueuePositions()
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun addDownloadItem(downloadItem: DownloadItem) {
    val existingItem = downloadItemQueue.find { it.id == downloadItem.id }
    if (existingItem != null) {
      return
    }
    downloadItem.queuePosition = downloadItemQueue.size
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    notifyQueueChanged()
  }

  @Synchronized
  fun retryDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (item.downloadItemParts.any { it in currentDownloadItemParts }) return false
    if (item.isDownloadFinished) return false
    synchronized(IncompleteDownloadCleanup) {
      var resetFailed = false
      item.downloadItemParts.filter { !it.moved }.forEach { part ->
        if (!resetPartForFreshDownload(part)) resetFailed = true
      }
      if (resetFailed) {
        item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
        persist(item, force = true)
        return false
      }
      item.terminalFailureAt = null
      item.stagingCleanupAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      persist(item, force = true)
    }
    clientEventEmitter.onDownloadItem(item)
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun resumeWork() {
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun pauseDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (item.isDownloadFinished || item.isFailed) return false

    item.isPaused = true
    item.downloadItemParts
            .filter { part -> !part.completed && !part.failed && !part.isMoving }
            .forEach { part ->
              activeTransferTokens.remove(part.id)
              activeCalls.remove(part.id)?.cancel()
              activePartUrls.remove(part.id)
              speedSamples.remove(part.id)
              currentDownloadItemParts.remove(part)
              reservations.remove(part.destinationPath)
              part.downloadId = null
              part.waitingForNetwork = false
              part.waitingForWifi = false
              part.waitingForRetry = false
              part.waitingForSpace = false
              part.retryAfterTime = null
              part.lastError = "Paused"
              part.bytesPerSecond = 0L
              part.lastUpdateTime = System.currentTimeMillis()
              clientEventEmitter.onDownloadItemPartUpdate(part)
            }
    persist(item, force = true)
    clientEventEmitter.onDownloadItem(item)
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun resumeDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (!item.isPaused) return false

    val resumedIndex = downloadItemQueue.indexOf(item)
    val activeIndex = currentDownloadItemParts.firstOrNull()?.downloadItemId?.let { activeId ->
      downloadItemQueue.indexOfFirst { it.id == activeId }
    } ?: -1
    item.isPaused = false
    item.downloadItemParts
            .filter { part -> !part.completed && !part.failed && !part.isMoving }
            .forEach { part ->
              part.waitingForNetwork = false
              part.waitingForWifi = false
              part.waitingForRetry = false
              part.waitingForSpace = false
              part.retryAfterTime = null
              part.lastError = null
            }
    persist(item, force = true)
    clientEventEmitter.onDownloadItem(item)
    if (activeIndex >= 0 && resumedIndex < activeIndex) preemptActivePartsForQueueChange()
    checkUpdateDownloadQueue()
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun pauseAllDownloadItems(): Boolean {
    var changed = false
    downloadItemQueue.map { it.id }.forEach { id ->
      if (pauseDownloadItem(id)) changed = true
    }
    return changed
  }

  @Synchronized
  fun resumeAllDownloadItems(): Boolean {
    var changed = false
    downloadItemQueue.map { it.id }.forEach { id ->
      if (resumeDownloadItem(id)) changed = true
    }
    return changed
  }

  @Synchronized
  fun allowCellularForAllDownloadItems(): Boolean {
    var changed = false
    downloadItemQueue.filter { !it.isDownloadFinished && !it.isFailed }.forEach { item ->
      if (!item.allowCellularDownload) {
        item.allowCellularDownload = true
        persist(item, force = true)
        clientEventEmitter.onDownloadItem(item)
        changed = true
      }
    }
    if (changed) checkUpdateDownloadQueue()
    notifyQueueChanged()
    return changed
  }

  @Synchronized
  fun retryFailedDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (!item.isFailed) return false

    retryDownloadItem(item)
    item.isPaused = false
    clientEventEmitter.onDownloadItem(item)
    checkUpdateDownloadQueue()
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun cancelDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    item.downloadItemParts.forEach { part ->
      activeTransferTokens.remove(part.id)
      activeCalls.remove(part.id)?.cancel()
      activePartUrls.remove(part.id)
      speedSamples.remove(part.id)
      currentDownloadItemParts.remove(part)
      reservations.remove(part.destinationPath)
      deletePartFiles(part)
    }
    downloadItemQueue.remove(item)
    DeviceManager.dbManager.removeDownloadItem(item.id)
    IncompleteDownloadCleanup.cancel(context, item.id)
    normalizeQueuePositions()
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun reorderDownloadItems(downloadItemIds: List<String>): Boolean {
    if (downloadItemIds.size != downloadItemQueue.size || downloadItemIds.distinct().size != downloadItemIds.size) {
      return false
    }
    val itemsById = downloadItemQueue.associateBy { it.id }
    if (downloadItemIds.any { it !in itemsById }) return false

    downloadItemQueue = downloadItemIds.mapNotNull(itemsById::get).toMutableList()
    preemptActivePartsForQueueChange()
    normalizeQueuePositions(forcePersist = true)
    downloadItemQueue.forEach(clientEventEmitter::onDownloadItem)
    checkUpdateDownloadQueue()
    notifyQueueChanged()
    return true
  }

  private fun retryDownloadItem(item: DownloadItem) {
    item.terminalFailureAt = null
    IncompleteDownloadCleanup.cancel(context, item.id)
    item.isPaused = false
    item.downloadItemParts.filter { it.failed }.forEach { part ->
      part.failed = false
      part.completed = false
      part.isMoving = false
      part.downloadId = null
      part.retryCount = 0
      part.waitingForNetwork = false
      part.waitingForWifi = false
      part.waitingForRetry = false
      part.waitingForSpace = false
      part.retryAfterTime = null
      part.lastError = null
      part.bytesPerSecond = 0L
    }
    persist(item, force = true)
  }

  @Synchronized
  fun cancelAll() {
    activeTransferTokens.clear()
    activeCalls.values.forEach(InternalDownloadManager.DownloadHandle::cancel)
    activeCalls.clear()
    activePartUrls.clear()
    speedSamples.clear()
    downloadItemQueue.forEach { item ->
      item.downloadItemParts.forEach(::deletePartFiles)
      IncompleteDownloadCleanup.cancel(context, item.id)
      DeviceManager.dbManager.removeDownloadItem(item.id)
    }
    currentDownloadItemParts.clear()
    reservations.clear()
    downloadItemQueue.clear()
    notifyQueueChanged()
  }

  @Synchronized
  fun hasWork(): Boolean =
          finalizingItems.isNotEmpty() ||
                  downloadItemQueue.any { item ->
                    if (item.isPaused || item.isFailed) return@any false
                    item.downloadItemParts.any { part ->
                      (!part.moved && !part.failed) || part.isMoving
                    }
                  }

  @Synchronized
  fun hasPausedItems(): Boolean = downloadItemQueue.any { it.isPaused && !it.isDownloadFinished }

  @Synchronized
  fun hasPausableItems(): Boolean = downloadItemQueue.any { item ->
    !item.isPaused && !item.isFailed && !item.isDownloadFinished
  }

  @Synchronized
  fun getQueueProgress(): QueueProgress {
    var knownBytes = 0L
    var downloadedKnownBytes = 0L
    downloadItemQueue.forEach { item ->
      item.downloadItemParts.forEach { part ->
        val size = if (part.fileSize > 0L) part.fileSize else if (part.completed) part.bytesDownloaded else 0L
        if (size <= 0L) return@forEach
        knownBytes += size
        downloadedKnownBytes += part.bytesDownloaded.coerceIn(0L, size)
      }
    }
    if (knownBytes <= 0L) return QueueProgress(0, false)
    return QueueProgress(((downloadedKnownBytes * 100L) / knownBytes).coerceIn(0L, 100L).toInt(), true)
  }

  @Synchronized
  fun getQueueStorageStats(): JSObject {
    data class MutableStorageStats(
            var remainingBytes: Long = 0L,
            var requiredBytes: Long = 0L,
            var unknownPartCount: Int = 0,
            var stagingFile: File? = null)

    val statsByLocation = mutableMapOf<String, MutableStorageStats>()
    downloadItemQueue.forEach { item ->
      item.downloadItemParts.filter { !it.completed }.forEach { part ->
        val staging = File(part.destinationPath)
        val stats = statsByLocation.getOrPut(storageKey(staging)) { MutableStorageStats() }
        val expectedSize = if (part.fileSize > 0L) part.fileSize else UNKNOWN_PART_RESERVATION_BYTES
        val stagedBytes = staging.takeIf(File::exists)?.length() ?: 0L
        val remaining = (expectedSize - stagedBytes).coerceAtLeast(0L)
        stats.remainingBytes += remaining
        stats.requiredBytes += if (part.isInternalStorage) remaining else remaining + expectedSize
        if (part.fileSize <= 0L) stats.unknownPartCount += 1
        stats.stagingFile = staging
      }
    }

    val locations = JSArray()
    statsByLocation.forEach { (key, stats) ->
      val staging = stats.stagingFile ?: return@forEach
      val fs = statFsFor(staging)
      val headroom = max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
      locations.put(
              JSObject()
                      .put("id", key)
                      .put("remainingBytes", stats.remainingBytes)
                      .put("requiredBytes", stats.requiredBytes)
                      .put("availableBytes", fs.availableBytes)
                      .put("headroomBytes", headroom)
                      .put("unknownPartCount", stats.unknownPartCount)
                      .put("hasSufficientSpace", fs.availableBytes >= stats.requiredBytes + headroom))
    }
    return JSObject().put("locations", locations)
  }

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    if (!hasNetworkConnection()) {
      pauseQueuedPartsForNetwork("Waiting for network")
      if (hasWork()) startWatchingDownloads() else notifyQueueChanged()
      return
    }

    val now = System.currentTimeMillis()
    val activeItemId = currentDownloadItemParts.firstOrNull()?.downloadItemId
    val item =
            activeItemId?.let { id -> downloadItemQueue.find { it.id == id } }
                    ?: downloadItemQueue.firstOrNull { queuedItem ->
                      !queuedItem.isPaused &&
                              !queuedItem.isFailed &&
                              queuedItem.downloadItemParts.any { part ->
                                (!part.completed && !part.failed) || part.isMoving
                              }
                    }
    if (item != null && !item.isPaused && !item.isFailed) {
      if (isActiveNetworkCellular() && !item.allowCellularDownload) {
        pauseItemForWifi(item)
        startWatchingDownloads()
        return
      }
      var slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      item.downloadItemParts
              .filter { part ->
                part.completed &&
                        !part.moved &&
                        !part.failed &&
                        !part.isMoving &&
                        part !in currentDownloadItemParts &&
                        File(part.destinationPath).exists() &&
                        !hasActiveDestinationConflict(part)
              }
              .take(slots.coerceAtLeast(0))
              .forEach { part ->
                currentDownloadItemParts.add(part)
                part.downloadId = APP_MANAGED_DOWNLOAD_ID
              }
      slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      val candidates =
              item.downloadItemParts.filter { part ->
                !part.completed && !part.failed && part.downloadId == null && !part.isMoving
              }.take(slots.coerceAtLeast(0))
      candidates.forEach { part ->
        if (currentDownloadItemParts.size >= MAX_SIMULTANEOUS_DOWNLOADS) return@forEach

        val retryAfter = part.retryAfterTime
        if (retryAfter != null && now < retryAfter) {
          if (!part.waitingForRetry) {
            part.waitingForRetry = true
            part.lastUpdateTime = now
            persist(item)
            clientEventEmitter.onDownloadItemPartUpdate(part)
          }
          return@forEach
        }

        part.waitingForNetwork = false
        part.waitingForWifi = false
        part.waitingForRetry = false
        part.retryAfterTime = null
        val existingFile = findSharedStorageFile(part)
        if (existingFile != null) {
          part.bytesDownloaded = existingFile.length()
          part.progress = 100L
          part.completedDestinationUri = existingFile.uri.toString()
          part.reusedExistingFile = true
          File(part.destinationPath).delete()
          completePart(item, part)
          clientEventEmitter.onDownloadItemPartUpdate(part)
          return@forEach
        }
        if (completeFromExistingInternalCover(item, part)) return@forEach
        if (hasActiveDestinationConflict(part)) {
          leaveQueued(item, part)
        } else if (part.fileSize <= 0L && currentDownloadItemParts.any { it.fileSize <= 0L }) {
          leaveQueued(item, part)
        } else if (tryReserve(part)) startDownload(item, part)
        else {
          part.waitingForSpace = true
          part.waitingForNetwork = false
          part.waitingForWifi = false
          part.waitingForRetry = false
          part.lastUpdateTime = System.currentTimeMillis()
          persist(item)
          clientEventEmitter.onDownloadItemPartUpdate(part)
        }
      }
    }
    if (hasWork()) startWatchingDownloads() else notifyQueueChanged()
  }

  private fun startDownload(item: DownloadItem, part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    if (part.fileSize > 0L && stagingFile.length() == part.fileSize) {
      part.bytesDownloaded = part.fileSize
      part.progress = 100L
      part.completed = true
      part.failed = false
      part.waitingForSpace = false
      part.waitingForNetwork = false
      part.waitingForWifi = false
      part.waitingForRetry = false
      part.retryAfterTime = null
      part.lastError = null
      part.bytesPerSecond = 0L
      part.lastUpdateTime = System.currentTimeMillis()
      persist(item, force = true)
      clientEventEmitter.onDownloadItemPartUpdate(part)
      if (part.isInternalStorage) finalizeInternalFile(item, part)
      else moveDownloadedFile(item, part)
      return
    }
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.waitingForSpace = false
    part.waitingForNetwork = false
    part.waitingForWifi = false
    part.waitingForRetry = false
    part.retryAfterTime = null
    part.lastError = null
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    persist(item, force = true)
    AbsLogger.info(tag, "Starting download for ${part.filename}")
    speedSamples[part.id] = SpeedSample(System.currentTimeMillis(), stagingFile.length())
    val downloadUrl = serverUrl(item, part)
    activePartUrls[part.id] = downloadUrl
    val transferToken = Any()
    activeTransferTokens[part.id] = transferToken
    val activeConfig = DeviceManager.serverConnectionConfig
    val token =
            if (activeConfig?.id == item.serverConnectionConfigId) activeConfig.token
            else
                    DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)?.token
                            ?: DeviceManager.token
    val handle =
            InternalDownloadManager(
                            stagingFile,
                            part.fileSize,
                            object : InternalProgressCallback {
                              override fun onSizeResolved(totalBytes: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts || totalBytes < 0L) return
                                  if (part.fileSize == totalBytes) return
                                  AbsLogger.info(
                                          tag,
                                          "Using server size $totalBytes instead of metadata size ${part.fileSize} for ${part.filename}"
                                  )
                                  part.fileSize = totalBytes
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  persist(item, force = true)
                                  clientEventEmitter.onDownloadItemPartUpdate(part)
                                }
                              }

                              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (activeTransferTokens[part.id] !== transferToken) return
                                  if (part !in currentDownloadItemParts) return
                                  part.bytesDownloaded = totalBytesWritten
                                  part.progress = progress
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  updateDownloadSpeed(part, totalBytesWritten)
                                  persist(item)
                                }
                              }

                              override fun onComplete(failed: Boolean) {
                                synchronized(this@DownloadItemManager) {
                                  if (activeTransferTokens[part.id] !== transferToken) return
                                  if (part !in currentDownloadItemParts) return
                                  activeTransferTokens.remove(part.id)
                                  part.failed = failed
                                  part.completed = !failed
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  activeCalls.remove(part.id)
                                  persist(item, force = true)
                                }
                              }

                              override fun onAuthError() {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts) return
                                  handleAuthError(item, part)
                                }
                              }
                            },
                            { hasAvailableSpace(part) }
                    )
                    .download(downloadUrl, token)
    if (part in currentDownloadItemParts && !part.completed && !part.failed) {
      activeCalls[part.id] = handle
    }
  }

  @Synchronized
  private fun startWatchingDownloads() {
    if (watcherRunning) return
    watcherRunning = true
    scope.launch {
      while (true) {
        val activeParts =
                synchronized(this@DownloadItemManager) { currentDownloadItemParts.toList() }
        if (!hasNetworkConnection()) {
          synchronized(this@DownloadItemManager) {
            lastActiveNetwork = null
            pauseActiveAndQueuedPartsForNetwork("Network unavailable")
          }
        } else {
          synchronized(this@DownloadItemManager) {
            enforceActiveNetworkPolicy()
            restartActivePartsForNetworkChange()
            restartActivePartsForAddressChange()
          }
          activeParts.forEach(::handlePartUpdate)
        }
        synchronized(this@DownloadItemManager) {
          checkUpdateDownloadQueue()
          if (!hasWork()) {
            watcherRunning = false
            notifyQueueChanged()
            return@launch
          }
        }
        delay(WATCH_INTERVAL_MS)
      }
    }
  }

  private fun handlePartUpdate(part: DownloadItemPart) {
    synchronized(this) {
      if (part !in currentDownloadItemParts) return
    }
    clientEventEmitter.onDownloadItemPartUpdate(part)
    val item =
            synchronized(this) { downloadItemQueue.find { it.id == part.downloadItemId } }
                    ?: run {
                      removeActivePart(part)
                      return
                    }
    if (!part.completed && !part.failed) {
      val lastUpdate = part.lastUpdateTime ?: return
      if (System.currentTimeMillis() - lastUpdate > STALL_TIMEOUT_MS) {
        AbsLogger.error(tag, "Download stalled: ${part.filename}")
        activeTransferTokens.remove(part.id)
        activeCalls.remove(part.id)?.cancel()
        failOrRetry(item, part, "Download stalled")
      }
      return
    }
    if (part.failed) {
      failOrRetry(item, part, "Transfer failed")
      return
    }
    if (part.isInternalStorage) finalizeInternalFile(item, part) else moveDownloadedFile(item, part)
  }

  @Synchronized
  private fun failOrRetry(item: DownloadItem, part: DownloadItemPart, reason: String) {
    if (!hasNetworkConnection()) {
      pausePartForNetwork(item, part, reason)
      return
    }

    removeActivePart(part)
    part.retryCount += 1
    reservations.remove(part.destinationPath)
    if (part.retryCount > MAX_RETRIES) {
      markTerminalFailure(item, part, "$reason after $MAX_RETRIES retries")
      return
    }
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    part.waitingForNetwork = false
    part.waitingForWifi = false
    part.waitingForRetry = true
    part.retryAfterTime = System.currentTimeMillis() + retryDelayMs(part.retryCount)
    part.lastError = reason
    part.bytesPerSecond = 0L
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  /** A 401 refreshes the token for this queued item's server without consuming transfer retries. */
  @Synchronized
  private fun handleAuthError(item: DownloadItem, part: DownloadItemPart) {
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.downloadId = null
    part.isMoving = false
    part.failed = false
    part.completed = false
    part.authRetryCount += 1
    part.lastUpdateTime = System.currentTimeMillis()
    if (part.authRetryCount > MAX_AUTH_RETRIES) {
      markTerminalFailure(item, part, "Unauthorized after $MAX_AUTH_RETRIES token refresh attempts")
      return
    }

    AbsLogger.info(
            tag,
            "Refreshing token after 401 for ${part.filename} (attempt ${part.authRetryCount})"
    )
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    refreshTokenThenResume(item.serverConnectionConfigId)
  }

  private fun refreshTokenThenResume(serverConnectionConfigId: String) {
    if (!refreshingServerIds.add(serverConnectionConfigId)) return
    apiHandler.refreshAuthTokens(serverConnectionConfigId) { result ->
      synchronized(this@DownloadItemManager) {
        refreshingServerIds.remove(serverConnectionConfigId)
        when (result) {
          is ApiHandler.RefreshResult.Success -> {
            AbsLogger.info(
                    tag,
                    "Token refresh succeeded; resuming downloads for $serverConnectionConfigId"
            )
            checkUpdateDownloadQueue()
          }
          ApiHandler.RefreshResult.Rejected -> failParkedAuthParts(serverConnectionConfigId)
          // Parked parts are still queued, so MAX_AUTH_RETRIES bounds the reattempts.
          ApiHandler.RefreshResult.Transient -> {
            AbsLogger.info(
                    tag,
                    "Token refresh could not be completed; retrying downloads for $serverConnectionConfigId"
            )
            checkUpdateDownloadQueue()
          }
        }
      }
    }
  }

  @Synchronized
  private fun failParkedAuthParts(serverConnectionConfigId: String) {
    downloadItemQueue.toList().forEach { item ->
      if (item.serverConnectionConfigId != serverConnectionConfigId) return@forEach
      item.downloadItemParts
              .filter {
                it.authRetryCount > 0 &&
                        !it.completed &&
                        !it.failed &&
                        it.downloadId == null &&
                        it !in currentDownloadItemParts
              }
              .forEach { part ->
                markTerminalFailure(item, part, "Unable to refresh download authorization")
              }
    }
  }

  @Synchronized
  private fun markTerminalFailure(item: DownloadItem, part: DownloadItemPart, reason: String) {
    AbsLogger.error(tag, "$reason: ${part.filename}")
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.failed = true
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
    item.stagingCleanupAt = null
    persist(item, force = true)
    IncompleteDownloadCleanup.schedule(context, item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    notifyQueueChanged()
  }

  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    part.isMoving = true
    val stagingFile = File(part.destinationPath)
    val finalFile = File(part.finalDestinationPath)
    finalFile.parentFile?.mkdirs()
    val backup = File(finalFile.parentFile, ".${finalFile.name}.abs-backup")
    try {
      if (backup.exists() && !backup.delete()) throw IllegalStateException("Could not clear backup")
      if (finalFile.exists() && !finalFile.renameTo(backup))
              throw IllegalStateException("Could not protect existing file")
      if (!stagingFile.renameTo(finalFile)) {
        if (backup.exists()) backup.renameTo(finalFile)
        throw IllegalStateException("Could not finalize internal staging file")
      }
      backup.delete()
      AbsLogger.info(tag, "Move completed for ${part.filename} to ${finalFile.absolutePath}")
      completePart(item, part)
    } catch (e: Exception) {
      part.isMoving = false
      part.failed = true
      failOrRetry(item, part, e.message ?: "Internal finalization failed")
    }
  }

  private fun moveDownloadedFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    val root =
            DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl))
                    ?: return failFinalization(item, part, "Could not resolve SAF destination")
    part.isMoving = true
    persist(item, force = true)
    scope.launch {
      try {
        if (!hasAvailableSpace(part))
                throw IllegalStateException("Insufficient storage for SAF copy")
        val folderKey = "${root.uri}/${part.finalDestinationSubfolder}"
        val folderLock = safFolderLocks.computeIfAbsent(folderKey) { Any() }
        val folder =
                synchronized(folderLock) { getOrCreateFolder(root, part.finalDestinationSubfolder) }
                        ?: throw IllegalStateException("Could not create SAF destination folder")
        val temporaryName = ".${part.filename}.${part.id.hashCode()}.part"
        folder.findFile(temporaryName)?.delete()
        val temporary =
                folder.createFile(mimeTypeFor(part), temporaryName)
                        ?: throw IllegalStateException("Could not create SAF temporary file")
        val staging = File(part.destinationPath)
        FileInputStream(staging).use { input ->
          context.contentResolver.openOutputStream(temporary.uri, "w")?.use { input.copyTo(it) }
                  ?: throw IllegalStateException("Could not open SAF output stream")
        }
        if (temporary.length() != staging.length())
                throw IllegalStateException("SAF copy size mismatch")
        val existing = findDocumentByFilename(folder, part)
        if (existing != null && !existing.delete())
                throw IllegalStateException("Could not replace existing file")
        if (!temporary.renameTo(part.filename))
                throw IllegalStateException("Could not finalize SAF temporary file")
        val destination =
                findDocumentByFilename(folder, part)
                        ?: throw IllegalStateException("Could not reopen finalized SAF file")
        if (destination.length() != staging.length())
                throw IllegalStateException("SAF final size mismatch")
        if (!staging.delete()) AbsLogger.error(tag, "Could not remove staging file ${staging.name}")
        part.completedDestinationUri = destination.uri.toString()
        AbsLogger.info(tag, "Move completed for ${part.filename} to ${destination.uri}")
        completePart(item, part)
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  @Synchronized
  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    AbsLogger.error(tag, message)
    part.isMoving = false
    part.failed = true
    failOrRetry(item, part, message)
  }

  @Synchronized
  private fun completePart(item: DownloadItem, part: DownloadItemPart) {
    part.moved = true
    part.completed = true
    part.failed = false
    part.isMoving = false
    part.downloadId = null
    part.waitingForNetwork = false
    part.waitingForWifi = false
    part.waitingForRetry = false
    part.waitingForSpace = false
    part.retryAfterTime = null
    part.lastError = null
    part.bytesPerSecond = 0L
    reservations.remove(part.destinationPath)
    removeActivePart(part)
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    checkDownloadItemFinished(item)
  }

  @Synchronized
  private fun checkDownloadItemFinished(item: DownloadItem) {
    if (!item.isDownloadFinished || !finalizingItems.add(item.id)) return
    IncompleteDownloadCleanup.cancel(context, item.id)
    scope.launch {
      try {
        val scanLock = scanLocks.computeIfAbsent(scanDestinationKey(item)) { Any() }
        synchronized(scanLock) {
          folderScanner.scanDownloadItem(item) { scanResult ->
            val event =
                    JSObject().apply {
                      put("libraryItemId", item.id)
                      put("localFolderId", item.localFolder.id)
                      scanResult?.localLibraryItem?.let {
                        put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(it)))
                      }
                      scanResult?.localMediaProgress?.let {
                        put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(it)))
                      }
                    }
            clientEventEmitter.onDownloadItemComplete(event)
            synchronized(this@DownloadItemManager) {
              downloadItemQueue.remove(item)
              DeviceManager.dbManager.removeDownloadItem(item.id)
              normalizeQueuePositions()
            }
          }
        }
      } catch (e: Exception) {
        // The files are already in place, so leave the item queued for restoreQueue to rescan.
        AbsLogger.error(tag, "Could not finalize download item ${item.id}: ${e.message}")
      } finally {
        synchronized(this@DownloadItemManager) {
          finalizingItems.remove(item.id)
          notifyQueueChanged()
        }
      }
    }
  }

  private fun tryReserve(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    staging.parentFile?.mkdirs()
    val expectedSize = if (part.fileSize > 0L) part.fileSize else UNKNOWN_PART_RESERVATION_BYTES
    val remaining =
            (expectedSize - (staging.takeIf(File::exists)?.length() ?: 0L)).coerceAtLeast(0L)
    val required = if (part.isInternalStorage) remaining else remaining + expectedSize
    val key = storageKey(staging)
    val fs = statFsFor(staging)
    val headroom = max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
    val alreadyReserved = reservations.filterKeys { storageKey(File(it)) == key }.values.sum()
    if (fs.availableBytes - alreadyReserved < required + headroom) return false
    reservations[part.destinationPath] = required
    return true
  }

  private fun hasAvailableSpace(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    val fs = statFsFor(staging)
    return fs.availableBytes >= max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
  }

  private fun statFsFor(staging: File): StatFs {
    var directory = staging.parentFile ?: context.filesDir
    directory.mkdirs()
    while (!directory.exists()) directory = directory.parentFile ?: context.filesDir
    return StatFs(directory.absolutePath)
  }

  private fun storageKey(file: File): String =
          if (file.absolutePath.startsWith(context.filesDir.absolutePath)) "internal"
          else "external"

  @Synchronized
  private fun removeActivePart(part: DownloadItemPart) {
    activeCalls.remove(part.id)
    activeTransferTokens.remove(part.id)
    activePartUrls.remove(part.id)
    speedSamples.remove(part.id)
    part.bytesPerSecond = 0L
    currentDownloadItemParts.remove(part)
  }

  @Synchronized
  private fun preemptActivePartsForQueueChange() {
    currentDownloadItemParts.toList().filter { !it.isMoving }.forEach { part ->
      val item = downloadItemQueue.find { it.id == part.downloadItemId } ?: return@forEach
      activeTransferTokens.remove(part.id)
      activeCalls.remove(part.id)?.cancel()
      activePartUrls.remove(part.id)
      speedSamples.remove(part.id)
      currentDownloadItemParts.remove(part)
      reservations.remove(part.destinationPath)
      part.downloadId = null
      part.failed = false
      part.completed = false
      part.waitingForNetwork = false
      part.waitingForWifi = false
      part.waitingForRetry = false
      part.waitingForSpace = false
      part.retryAfterTime = null
      part.lastError = null
      part.bytesPerSecond = 0L
      part.lastUpdateTime = System.currentTimeMillis()
      persist(item, force = true)
      clientEventEmitter.onDownloadItemPartUpdate(part)
    }
  }

  private fun normalizeQueuePositions(forcePersist: Boolean = false) {
    downloadItemQueue.forEachIndexed { index, item ->
      if (item.queuePosition != index || forcePersist) {
        item.queuePosition = index
        persist(item, force = true)
      }
    }
  }

  private fun updateDownloadSpeed(part: DownloadItemPart, totalBytesWritten: Long) {
    val now = System.currentTimeMillis()
    val previous = speedSamples[part.id]
    if (previous == null) {
      speedSamples[part.id] = SpeedSample(now, totalBytesWritten)
      return
    }
    val elapsed = now - previous.timestamp
    if (elapsed < SPEED_SAMPLE_INTERVAL_MS || totalBytesWritten < previous.bytesDownloaded) return
    val currentSpeed = ((totalBytesWritten - previous.bytesDownloaded) * 1_000L / elapsed).coerceAtLeast(0L)
    part.bytesPerSecond =
            if (part.bytesPerSecond <= 0L) currentSpeed
            else (part.bytesPerSecond * 3L + currentSpeed) / 4L
    speedSamples[part.id] = SpeedSample(now, totalBytesWritten)
  }

  private fun hasNetworkConnection(): Boolean = DeviceManager.checkConnectivity(context)

  private fun isActiveNetworkCellular(): Boolean {
    val network = connectivityManager.activeNetwork ?: return false
    return connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
  }

  @Synchronized
  private fun enforceActiveNetworkPolicy() {
    if (!isActiveNetworkCellular()) return
    currentDownloadItemParts.mapNotNull { part ->
      downloadItemQueue.find { it.id == part.downloadItemId }
    }.distinctBy { it.id }.filter { !it.allowCellularDownload }.forEach(::pauseItemForWifi)
  }

  @Synchronized
  private fun pauseItemForWifi(item: DownloadItem) {
    item.downloadItemParts.filter { !it.completed && !it.isMoving }.forEach { part ->
      var changed = part.failed
      part.failed = false
      if (part.downloadId != null || part in currentDownloadItemParts) {
        activeTransferTokens.remove(part.id)
        activeCalls.remove(part.id)?.cancel()
        activePartUrls.remove(part.id)
        speedSamples.remove(part.id)
        currentDownloadItemParts.remove(part)
        reservations.remove(part.destinationPath)
        part.downloadId = null
        changed = true
      }
      markPartWaitingForNetwork(item, part, "Waiting for Wi-Fi", waitingForWifi = true, force = changed)
    }
  }

  @Synchronized
  private fun pauseQueuedPartsForNetwork(reason: String) {
    downloadItemQueue.filter { it.terminalFailureAt == null }.forEach { item ->
      item.downloadItemParts
              .filter { part ->
                !part.completed && part.downloadId == null && !part.isMoving
              }
              .forEach { part ->
                val wasFailed = part.failed
                part.failed = false
                markPartWaitingForNetwork(item, part, reason, force = wasFailed)
              }
    }
  }

  @Synchronized
  private fun pauseActiveAndQueuedPartsForNetwork(reason: String) {
    downloadItemQueue.filter { it.terminalFailureAt == null }.forEach { item ->
      item.downloadItemParts
              .filter { part ->
                !part.completed && !part.isMoving
              }
              .forEach { part ->
                var changed = part.failed
                part.failed = false
                if (part.downloadId != null || part in currentDownloadItemParts) {
                  activeTransferTokens.remove(part.id)
                  activeCalls.remove(part.id)?.cancel()
                  activePartUrls.remove(part.id)
                  speedSamples.remove(part.id)
                  currentDownloadItemParts.remove(part)
                  reservations.remove(part.destinationPath)
                  part.downloadId = null
                  changed = true
                }
                markPartWaitingForNetwork(item, part, reason, force = changed)
              }
    }
  }

  @Synchronized
  private fun pausePartForNetwork(item: DownloadItem, part: DownloadItemPart, reason: String) {
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    markPartWaitingForNetwork(item, part, reason, force = true)
  }

  private fun markPartWaitingForNetwork(
          item: DownloadItem,
          part: DownloadItemPart,
          reason: String,
          waitingForWifi: Boolean = false,
          force: Boolean = false
  ) {
    val changed =
            force ||
                    !part.waitingForNetwork ||
                    part.waitingForWifi != waitingForWifi ||
                    part.waitingForRetry ||
                    part.waitingForSpace ||
                    part.retryAfterTime != null ||
                    part.lastError != reason
    if (!changed) return

    part.waitingForNetwork = true
    part.waitingForWifi = waitingForWifi
    part.waitingForRetry = false
    part.waitingForSpace = false
    part.retryAfterTime = null
    part.lastError = reason
    part.bytesPerSecond = 0L
    part.lastUpdateTime = System.currentTimeMillis()
    persist(item, force = force)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  private fun deletePartFiles(part: DownloadItemPart) {
    deleteAppOwnedFile(part.destinationPath)
    if (part.isInternalStorage && part.moved && !part.reusedExistingFile) {
      deleteAppOwnedFile(part.finalDestinationPath)
    } else if (!part.isInternalStorage && part.moved && !part.reusedExistingFile) {
      part.completedDestinationUri?.let { uriString ->
        try {
          DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.delete()
        } catch (e: Exception) {
          AbsLogger.error(tag, "Could not delete SAF download file for ${part.filename}: ${e.message}")
        }
      }
    }
  }

  private fun deleteAppOwnedFile(filePath: String?) {
    if (filePath.isNullOrBlank()) return
    val file = File(filePath)
    val path = file.absolutePath
    val internal = context.filesDir.absolutePath
    val external = context.getExternalFilesDir(null)?.absolutePath
    if (path.startsWith(internal) || (external != null && path.startsWith(external))) {
      if (file.exists() && !file.delete()) AbsLogger.error(tag, "Could not delete download file $path")
      file.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    } else {
      AbsLogger.error(tag, "Refusing to delete non-app-owned path $path")
    }
  }

  @Synchronized
  private fun restartActivePartsForNetworkChange() {
    val activeNetwork = connectivityManager.activeNetwork
    val previousNetwork = lastActiveNetwork
    lastActiveNetwork = activeNetwork
    if (activeNetwork == null || previousNetwork == null || activeNetwork == previousNetwork) return

    AbsLogger.info(tag, "Active network changed; resuming downloads on the new network")
    restartActiveParts("Network changed, resuming")
  }

  @Synchronized
  private fun restartActivePartsForAddressChange() {
    val addressChanged = currentDownloadItemParts.any { part ->
      val item = downloadItemQueue.find { it.id == part.downloadItemId } ?: return@any false
      val currentUrl = serverUrl(item, part)
      activePartUrls[part.id] != currentUrl
    }
    if (!addressChanged) return

    AbsLogger.info(tag, "Download address changed; resuming active downloads")
    restartActiveParts("Connection changed, resuming")
  }

  @Synchronized
  private fun restartActiveParts(reason: String) {
    currentDownloadItemParts.toList().filter { !it.isMoving }.forEach { part ->
      val item = downloadItemQueue.find { it.id == part.downloadItemId } ?: return@forEach
      activeTransferTokens.remove(part.id)
      activeCalls.remove(part.id)?.cancel()
      activePartUrls.remove(part.id)
      speedSamples.remove(part.id)
      currentDownloadItemParts.remove(part)
      reservations.remove(part.destinationPath)
      part.downloadId = null
      part.failed = false
      part.completed = false
      part.isMoving = false
      part.waitingForNetwork = false
      part.waitingForWifi = false
      part.waitingForRetry = false
      part.waitingForSpace = false
      part.retryAfterTime = null
      part.lastError = reason
      part.bytesPerSecond = 0L
      part.lastUpdateTime = System.currentTimeMillis()
      persist(item, force = true)
      clientEventEmitter.onDownloadItemPartUpdate(part)
    }
  }

  private fun hasValidDownloadPaths(item: DownloadItem): Boolean {
    if (item.id.isBlank() || item.downloadItemParts.isEmpty()) return false
    return item.downloadItemParts.all { part ->
      val destinationPath: String? = part.destinationPath
      val finalDestinationPath: String? = part.finalDestinationPath
      val serverPath: String? = part.serverPath
      val id: String? = part.id
      !destinationPath.isNullOrBlank() &&
              !finalDestinationPath.isNullOrBlank() &&
              !serverPath.isNullOrBlank() &&
              !id.isNullOrBlank()
    }
  }

  private fun retryDelayMs(retryCount: Int): Long {
    val index = (retryCount - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)
    return RETRY_DELAYS_MS[index]
  }

  private fun persist(item: DownloadItem, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - (lastPersistTime[item.id] ?: 0L) < PERSIST_INTERVAL_MS) return
    lastPersistTime[item.id] = now
    DeviceManager.dbManager.saveDownloadItem(item)
  }

  private fun notifyQueueChanged() {
    clientEventEmitter.onQueueChanged(hasWork())
  }

  fun destroy() {
    activeTransferTokens.clear()
    activeCalls.values.forEach(InternalDownloadManager.DownloadHandle::cancel)
    activeCalls.clear()
    speedSamples.clear()
    scope.cancel()
  }

  private fun getOrCreateFolder(root: DocumentFile, relativePath: String): DocumentFile? {
    var current = root
    relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
    }
    return current
  }

  private fun findSharedStorageFile(part: DownloadItemPart): DocumentFile? {
    if (part.isInternalStorage) return null
    val root = DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl)) ?: return null
    var folder = root
    part.finalDestinationSubfolder.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      folder = folder.findFile(segment) ?: return null
    }
    val file = findDocumentByFilename(folder, part) ?: return null
    if (!file.isFile) return null
    if (part.fileSize > 0L && file.length() != part.fileSize) return null
    if (part.fileSize <= 0L && file.length() <= 0L) return null
    return file
  }

  private fun completeFromExistingInternalCover(
          item: DownloadItem,
          part: DownloadItemPart
  ): Boolean {
    if (!part.isInternalStorage || !part.serverPath.endsWith("/cover")) return false
    val file = File(part.finalDestinationPath)
    if (!file.isFile || file.length() <= 0L) return false
    if (part.fileSize > 0L && file.length() != part.fileSize) return false
    part.bytesDownloaded = file.length()
    part.progress = 100L
    part.reusedExistingFile = true
    AbsLogger.info(tag, "Reusing existing cover ${part.filename}")
    File(part.destinationPath).delete()
    completePart(item, part)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    return true
  }

  private fun hasActiveDestinationConflict(part: DownloadItemPart): Boolean =
          currentDownloadItemParts.any { activePart ->
            activePart !== part &&
                    activePart.localFolderId == part.localFolderId &&
                    activePart.finalDestinationPath == part.finalDestinationPath
          }

  private fun leaveQueued(item: DownloadItem, part: DownloadItemPart) {
    if (!part.waitingForSpace) return
    part.waitingForSpace = false
    part.downloadId = null
    persist(item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  private fun scanDestinationKey(item: DownloadItem): String =
          "${item.localFolder.id}:${item.itemFolderPath}"

  private fun finalizedFileExists(part: DownloadItemPart): Boolean {
    if (part.isInternalStorage) {
      val file = File(part.finalDestinationPath)
      return file.isFile &&
              if (part.fileSize > 0L) file.length() == part.fileSize else file.length() > 0L
    }
    part.completedDestinationUri?.let { uri ->
      try {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri))
        if (file?.isFile == true && (part.fileSize <= 0L || file.length() == part.fileSize))
                return true
      } catch (e: Exception) {
        AbsLogger.error(tag, "Could not validate SAF file ${part.filename}: ${e.message}")
      }
    }
    return findSharedStorageFile(part) != null
  }

  /** Resets an unmoved part when recovery crosses a service-session boundary. */
  private fun resetPartForFreshDownload(part: DownloadItemPart): Boolean {
    val stagingFile = File(part.destinationPath)
    if (stagingFile.exists() && !stagingFile.delete()) {
      AbsLogger.error(tag, "Could not delete staging file ${part.filename}")
      part.failed = true
      return false
    }
    part.completed = false
    part.bytesDownloaded = 0L
    part.progress = 0L
    part.failed = false
    part.isMoving = false
    part.downloadId = null
    part.retryCount = 0
    part.authRetryCount = 0
    part.waitingForSpace = false
    part.reusedExistingFile = false
    return true
  }

  private fun findDocumentByFilename(folder: DocumentFile, part: DownloadItemPart): DocumentFile? {
    folder.findFile(part.filename)?.let {
      return it
    }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null &&
                      document.isFile &&
                      ((document.name ?: "").substringBeforeLast('.') == expectedBaseName ||
                              document.fullName.substringBeforeLast('.') == expectedBaseName))
    }
  }

  private fun mimeTypeFor(part: DownloadItemPart): String =
          part.audioTrack?.mimeType
                  ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
                    "epub" -> "application/epub+zip"
                    "pdf" -> "application/pdf"
                    else -> "image/jpeg"
                  }

  private fun serverUrl(item: DownloadItem, part: DownloadItemPart): String {
    val rawCover = if (part.serverPath.endsWith("/cover")) "?raw=1" else ""
    val config = DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)
    val activeAddress = DeviceManager.getServerAddress(config).ifEmpty { item.serverAddress }
    return "$activeAddress${part.serverPath}$rawCover"
  }

  private companion object {
    data class SpeedSample(val timestamp: Long, val bytesDownloaded: Long)

    const val APP_MANAGED_DOWNLOAD_ID = -1L
    const val MAX_SIMULTANEOUS_DOWNLOADS = 3
    const val WATCH_INTERVAL_MS = 1_000L
    const val STALL_TIMEOUT_MS = 60_000L
    const val MAX_RETRIES = 5
    const val MAX_AUTH_RETRIES = 2
    const val PERSIST_INTERVAL_MS = 2_000L
    const val SPEED_SAMPLE_INTERVAL_MS = 500L
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val UNKNOWN_PART_RESERVATION_BYTES = 100L * 1024L * 1024L
    val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L)
  }

  data class QueueProgress(val progress: Int, val determinate: Boolean)
}
