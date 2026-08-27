package com.audiobookshelf.app.services

import android.content.Context
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.managers.IncompleteDownloadCleanup
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLogger
import com.getcapacitor.JSObject
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared process owner used by the foreground service and the Capacitor bridge. */
object DownloadServiceHost {
  enum class ExistingDownloadResult { NOT_FOUND, ACTIVE, RETRIED, SERVICE_START_FAILED }

  data class NotificationStrings(
          val preparing: String,
          val downloadingFile: String,
          val waitingForStorage: String,
          val waitingForNetwork: String,
          val waitingForWifi: String,
          val paused: String,
          val downloads: String,
          val cancel: String,
          val pause: String,
          val resume: String
  )

  private var manager: DownloadItemManager? = null
  private var bridgeEmitter: DownloadItemManager.DownloadEventEmitter = NoopEmitter
  @Volatile private var service: DownloadService? = null
  @Volatile private var bridgeReady = false
  private val deferredCompletions = Collections.synchronizedList(mutableListOf<JSObject>())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var restoreJob: Job? = null

  @Synchronized
  fun ensure(context: Context): DownloadItemManager {
    if (manager == null) {
      val appContext = context.applicationContext
      DbManager.initialize(appContext)
      manager = DownloadItemManager(FolderScanner(appContext), appContext, ForwardingEmitter)
      restoreJob = scope.launch {
        IncompleteDownloadCleanup.cleanupExpired(appContext)
        manager!!.restoreQueue()
        onRestoreComplete(appContext)
      }
    }
    return manager!!
  }

  /** Attaches the frontend after restored queue items have been emitted. */
  @Synchronized
  fun attachBridge(context: Context, emitter: DownloadItemManager.DownloadEventEmitter) {
    bridgeReady = false
    bridgeEmitter = emitter
    ensure(context).setEventEmitter(ForwardingEmitter)
    bridgeReady = true
    val completions = synchronized(deferredCompletions) {
      deferredCompletions.toList().also { deferredCompletions.clear() }
    }
    completions.forEach(bridgeEmitter::onDownloadItemComplete)
  }

  @Synchronized
  fun detachBridge() {
    bridgeReady = false
    bridgeEmitter = NoopEmitter
  }

  fun enqueue(context: Context, item: DownloadItem, callback: (String?) -> Unit) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.addDownloadItem(item)
      if (startService(context)) callback(null)
      else callback("Unable to start the Android download service")
    }
  }

  fun retryExisting(
          context: Context,
          downloadItemId: String,
          callback: (ExistingDownloadResult) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      val existing = queue.downloadItemQueue.find { it.id == downloadItemId }
      if (existing == null) {
        callback(ExistingDownloadResult.NOT_FOUND)
      } else if (!queue.retryDownloadItem(downloadItemId)) {
        callback(ExistingDownloadResult.ACTIVE)
      } else if (startService(context)) {
        callback(ExistingDownloadResult.RETRIED)
      } else {
        callback(ExistingDownloadResult.SERVICE_START_FAILED)
      }
    }
  }

  fun cancelAll(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.cancelAll()
    }
  }

  @Synchronized
  fun pause(context: Context, downloadItemId: String): Boolean = ensure(context).pauseDownloadItem(downloadItemId)

  @Synchronized
  fun pauseAll(context: Context): Boolean = ensure(context).pauseAllDownloadItems()

  @Synchronized
  fun resume(context: Context, downloadItemId: String): Boolean {
    val resumed = ensure(context).resumeDownloadItem(downloadItemId)
    if (resumed) startService(context)
    return resumed
  }

  @Synchronized
  fun resumeAll(context: Context): Boolean {
    val resumed = ensure(context).resumeAllDownloadItems()
    if (resumed) startService(context)
    return resumed
  }

  @Synchronized
  fun allowCellularForAll(context: Context): Boolean {
    val changed = ensure(context).allowCellularForAllDownloadItems()
    if (changed) startService(context)
    return changed
  }

  @Synchronized
  fun retry(context: Context, downloadItemId: String): Boolean {
    val retried = ensure(context).retryFailedDownloadItem(downloadItemId)
    if (retried) startService(context)
    return retried
  }

  @Synchronized
  fun cancel(context: Context, downloadItemId: String): Boolean = ensure(context).cancelDownloadItem(downloadItemId)

  @Synchronized
  fun reorder(context: Context, downloadItemIds: List<String>): Boolean {
    val reordered = ensure(context).reorderDownloadItems(downloadItemIds)
    if (reordered && ensure(context).hasWork()) startService(context)
    return reordered
  }

  fun setNotificationStrings(
          context: Context,
          preparing: String,
          downloadingFile: String,
          waitingForStorage: String,
          waitingForNetwork: String,
          waitingForWifi: String,
          paused: String,
          downloads: String,
          cancel: String,
          pause: String,
          resume: String
  ) {
    context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREPARING, preparing)
            .putString(KEY_DOWNLOADING_FILE, downloadingFile)
            .putString(KEY_WAITING_FOR_STORAGE, waitingForStorage)
            .putString(KEY_WAITING_FOR_NETWORK, waitingForNetwork)
            .putString(KEY_WAITING_FOR_WIFI, waitingForWifi)
            .putString(KEY_PAUSED, paused)
            .putString(KEY_DOWNLOADS, downloads)
            .putString(KEY_CANCEL, cancel)
            .putString(KEY_PAUSE, pause)
            .putString(KEY_RESUME, resume)
            .apply()
  }

  fun notificationStrings(context: Context): NotificationStrings {
    val preferences = context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    return NotificationStrings(
            preferences.getString(KEY_PREPARING, DEFAULT_PREPARING) ?: DEFAULT_PREPARING,
            preferences.getString(KEY_DOWNLOADING_FILE, DEFAULT_DOWNLOADING_FILE)
                    ?: DEFAULT_DOWNLOADING_FILE,
            preferences.getString(KEY_WAITING_FOR_STORAGE, DEFAULT_WAITING_FOR_STORAGE)
                    ?: DEFAULT_WAITING_FOR_STORAGE,
            preferences.getString(KEY_WAITING_FOR_NETWORK, DEFAULT_WAITING_FOR_NETWORK)
                    ?: DEFAULT_WAITING_FOR_NETWORK,
            preferences.getString(KEY_WAITING_FOR_WIFI, DEFAULT_WAITING_FOR_WIFI)
                    ?: DEFAULT_WAITING_FOR_WIFI,
            preferences.getString(KEY_PAUSED, DEFAULT_PAUSED) ?: DEFAULT_PAUSED,
            preferences.getString(KEY_DOWNLOADS, DEFAULT_DOWNLOADS) ?: DEFAULT_DOWNLOADS,
            preferences.getString(KEY_CANCEL, DEFAULT_CANCEL) ?: DEFAULT_CANCEL,
            preferences.getString(KEY_PAUSE, DEFAULT_PAUSE) ?: DEFAULT_PAUSE,
            preferences.getString(KEY_RESUME, DEFAULT_RESUME) ?: DEFAULT_RESUME)
  }

  fun attachService(downloadService: DownloadService) {
    synchronized(this) { service = downloadService }
    startWork(downloadService)
  }

  @Synchronized
  fun detachService(downloadService: DownloadService) {
    if (service === downloadService) service = null
  }

  fun startWork(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.resumeWork()
    }
  }

  private fun onRestoreComplete(context: Context) {
    val attachedService = synchronized(this) { service }
    if (attachedService != null) {
      manager?.resumeWork()
    } else if (bridgeReady && manager?.hasWork() == true) {
      startService(context)
    }
  }

  private fun startService(context: Context): Boolean {
    return try {
      ContextCompat.startForegroundService(context, DownloadService.intent(context))
      true
    } catch (e: RuntimeException) {
      AbsLogger.error(TAG, "Could not start download foreground service: ${e.message}")
      false
    }
  }

  private object ForwardingEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) { bridgeEmitter.onDownloadItem(downloadItem) }
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) {
      if (bridgeReady) bridgeEmitter.onDownloadItemPartUpdate(downloadItemPart)
      service?.onPartUpdate(downloadItemPart)
    }
    override fun onDownloadItemComplete(jsobj: JSObject) {
      if (bridgeReady) bridgeEmitter.onDownloadItemComplete(jsobj) else deferredCompletions.add(jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      bridgeEmitter.onQueueChanged(hasWork)
      service?.onQueueChanged(hasWork, manager?.hasPausedItems() == true)
    }
  }

  private object NoopEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) = Unit
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) = Unit
    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
    override fun onQueueChanged(hasWork: Boolean) = Unit
  }

  private const val NOTIFICATION_PREFERENCES = "download_notifications"
  private const val KEY_PREPARING = "preparing"
  private const val KEY_DOWNLOADING_FILE = "downloading_file"
  private const val KEY_WAITING_FOR_STORAGE = "waiting_for_storage"
  private const val KEY_WAITING_FOR_NETWORK = "waiting_for_network"
  private const val KEY_WAITING_FOR_WIFI = "waiting_for_wifi"
  private const val KEY_PAUSED = "paused"
  private const val KEY_DOWNLOADS = "downloads"
  private const val KEY_CANCEL = "cancel"
  private const val KEY_PAUSE = "pause"
  private const val KEY_RESUME = "resume"
  private const val DEFAULT_PREPARING = "Preparing downloads"
  private const val DEFAULT_DOWNLOADING_FILE = "Downloading {0}"
  private const val DEFAULT_WAITING_FOR_STORAGE = "Waiting for available storage"
  private const val DEFAULT_WAITING_FOR_NETWORK = "Waiting for network"
  private const val DEFAULT_WAITING_FOR_WIFI = "Waiting for Wi-Fi"
  private const val DEFAULT_PAUSED = "Downloads paused"
  private const val DEFAULT_DOWNLOADS = "Downloads"
  private const val DEFAULT_CANCEL = "Cancel"
  private const val TAG = "DownloadServiceHost"
  private const val DEFAULT_PAUSE = "Pause"
  private const val DEFAULT_RESUME = "Resume"
}
