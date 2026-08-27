<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <div class="mb-5 flex items-center justify-between">
      <p class="text-base font-semibold text-fg">{{ $strings.HeaderDownloads }}</p>
      <div class="flex flex-wrap items-center justify-end gap-x-3 gap-y-1">
        <button v-if="hasPausableItems" class="flex items-center gap-1 text-sm font-semibold text-fg-muted hover:text-fg" type="button" @click="pauseAllDownloads">
          <span class="material-symbols text-lg">pause</span>
          <span>{{ $strings.ButtonPauseAll }}</span>
        </button>
        <button v-else-if="hasPausedItems" class="flex items-center gap-1 text-sm font-semibold text-success" type="button" @click="resumeAllDownloads">
          <span class="material-symbols text-lg">play_arrow</span>
          <span>{{ $strings.ButtonResumeAll }}</span>
        </button>
        <button v-if="canContinueOnCellular" class="flex items-center gap-1 text-sm font-semibold text-warning" type="button" @click="continueOnCellular">
          <span class="material-symbols text-lg">signal_cellular_alt</span>
          <span>{{ $strings.ButtonUseMobileData }}</span>
        </button>
        <button v-if="downloadItems.length" class="flex items-center gap-1 text-sm font-semibold text-error" type="button" @click="removeAllDownloads">
          <span class="material-symbols text-lg">delete_sweep</span>
          <span>{{ $strings.ButtonRemoveAll }}</span>
        </button>
        <p class="text-sm text-fg-muted">{{ downloadItems.length }} items</p>
      </div>
    </div>

    <div v-if="storageLocations.length" class="mb-5 rounded-md border border-border bg-primary p-3">
      <div v-for="location in storageLocations" :key="location.id" class="text-sm" :class="location.hasSufficientSpace ? 'text-fg-muted' : 'text-error'">
        <span class="font-semibold">{{ getStorageLocationName(location.id) }}:</span>
        {{ $getString('MessageDownloadQueueStorageStatus', [$bytesPretty(location.requiredBytes), $bytesPretty(location.availableBytes)]) }}
        <span v-if="location.unknownPartCount"> {{ $strings.MessageDownloadQueueStorageEstimated }}</span>
        <span v-if="!location.hasSufficientSpace" class="block text-xs font-semibold">{{ $strings.MessageDownloadQueueInsufficientStorage }}</span>
      </div>
    </div>

    <div v-if="!downloadItems.length" class="py-10 text-center">
      <p class="text-base text-fg-muted">No active downloads</p>
    </div>

    <draggable v-model="downloadItems" v-bind="dragOptions" handle=".drag-handle" draggable=".download-queue-item" :disabled="!canReorder" @end="onQueueReordered">
      <div v-for="group in downloadGroups" :key="group.item.id" class="download-queue-item relative mb-3 border-b border-border pb-3 last:border-b-0" :class="canReorder ? 'pl-12' : ''">
      <div v-if="canReorder" class="drag-handle absolute inset-y-0 left-0 flex w-12 cursor-move items-center justify-center text-fg-muted active:text-fg" title="Drag to prioritize">
        <span class="material-symbols text-2xl">drag_indicator</span>
      </div>
      <div class="mb-2 flex items-start justify-between gap-3">
        <div class="min-w-0 flex-grow">
          <button class="block max-w-full truncate text-left text-base font-semibold text-fg hover:underline" type="button" @click="openItem(group.item)">{{ group.item.itemTitle }}</button>
          <p class="text-xs text-fg-muted">
            {{ group.status }}<span v-if="group.speed"> · {{ formatSpeed(group.speed) }}</span>
          </p>
          <button v-if="group.etaSeconds !== null" class="text-left text-xs text-info hover:underline" type="button" :title="$strings.MessageDownloadEtaToggle" @click="toggleEtaDisplay">
            {{ getEtaText(group.etaSeconds) }}
          </button>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <p class="text-sm font-semibold text-fg">{{ Math.round(group.progress * 100) }}%</p>
          <button v-if="canPause(group)" class="text-fg-muted hover:text-fg" type="button" :title="$strings.ButtonPause" @click="pauseDownload(group.item)">
            <span class="material-symbols text-xl">pause</span>
          </button>
          <button v-if="canResume(group)" class="text-fg-muted hover:text-fg" type="button" :title="$strings.ButtonResume" @click="resumeDownload(group.item)">
            <span class="material-symbols text-xl">play_arrow</span>
          </button>
          <button v-if="canRetry(group)" class="text-fg-muted hover:text-fg" type="button" :title="$strings.ButtonRetry" @click="retryDownload(group.item)">
            <span class="material-symbols text-xl">refresh</span>
          </button>
          <button class="text-fg-muted hover:text-error" type="button" :title="canRetry(group) ? $strings.ButtonClear : $strings.ButtonCancel" @click="cancelDownload(group.item)">
            <span class="material-symbols text-xl">{{ canRetry(group) ? 'close' : 'cancel' }}</span>
          </button>
        </div>
      </div>

      <div class="mb-2 h-1.5 overflow-hidden rounded-sm bg-primary">
        <div class="h-full bg-success transition-all" :style="{ width: `${Math.round(group.progress * 100)}%` }" />
      </div>

      <button v-if="group.parts.length > condensedPartLimit" class="flex items-center gap-1 text-xs text-fg-muted hover:text-fg" type="button" @click="toggleParts(group.item.id)">
        <span class="material-symbols text-base">{{ arePartsExpanded(group.item.id) ? 'expand_less' : 'expand_more' }}</span>
        <span>{{ $getString('MessageDownloadFileCount', [group.parts.length]) }} · {{ arePartsExpanded(group.item.id) ? $strings.ButtonHideDetails : $strings.ButtonShowDetails }}</span>
      </button>

      <div v-if="group.parts.length <= condensedPartLimit || arePartsExpanded(group.item.id)" class="space-y-1" :class="group.parts.length > condensedPartLimit ? 'mt-2' : ''">
        <div v-for="itemPart in group.parts" :key="`${itemPart.downloadItemId}-${itemPart.id}`" class="flex items-start gap-3">
          <div class="mt-0.5 w-7 shrink-0 text-center">
            <span v-if="itemPart.completed" class="material-symbols text-xl text-success">check_circle</span>
            <span v-else-if="itemPart.failed" class="material-symbols text-xl text-error">error</span>
            <span v-else-if="itemPart.waitingForNetwork" class="material-symbols text-xl text-warning">cloud_off</span>
            <span v-else-if="itemPart.waitingForRetry" class="material-symbols text-xl text-warning">schedule</span>
            <span v-else-if="itemPart.downloadId == null" class="material-symbols text-xl text-fg-muted">schedule</span>
            <span v-else class="material-symbols text-xl text-info">downloading</span>
          </div>
          <div class="min-w-0 flex-grow">
            <div class="flex items-center justify-between gap-3">
              <p class="truncate text-sm text-fg">{{ itemPart.filename }}</p>
              <p class="shrink-0 text-xs text-fg-muted">
                {{ Math.round(itemPart.progress) }}%<span v-if="itemPart.bytesPerSecond"> · {{ formatSpeed(itemPart.bytesPerSecond) }}</span>
              </p>
            </div>
            <p v-if="getStatusText(itemPart)" class="text-xs text-fg-muted">
              {{ getStatusText(itemPart) }}
            </p>
          </div>
        </div>
      </div>
      </div>
    </draggable>
  </div>
</template>

<script>
import draggable from 'vuedraggable'
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  components: { draggable },
  data() {
    return {
      now: Date.now(),
      countdownTimer: null,
      lastKnownQueueSpeed: 0,
      showAbsoluteEta: false,
      condensedPartLimit: 2,
      expandedDownloadItems: {},
      storageLocations: [],
      storageRefreshTick: 0,
      dragOptions: {
        animation: 200,
        delay: 80,
        delayOnTouchOnly: true
      }
    }
  },
  computed: {
    downloadItems: {
      get() {
        return this.$store.state.globals.itemDownloads
      },
      set(downloadItems) {
        this.$store.commit('globals/setItemDownloadsOrder', downloadItems)
      }
    },
    canReorder() {
      return this.$platform === 'android' && this.downloadItems.length > 1
    },
    hasPausableItems() {
      return this.downloadItems.some((item) => !item.isPaused && !this.isFailedItem(item) && this.getItemProgress(item.downloadItemParts || []) < 1)
    },
    hasPausedItems() {
      return this.downloadItems.some((item) => item.isPaused)
    },
    canContinueOnCellular() {
      return this.$store.getters['getCanDownloadUsingCellular'] !== 'NEVER' && this.downloadItems.some((item) =>
        (item.downloadItemParts || []).some((part) => part.waitingForWifi)
      )
    },
    currentQueueSpeed() {
      return this.downloadItems.reduce(
        (itemTotal, item) => itemTotal + (item.downloadItemParts || []).reduce((partTotal, part) => partTotal + Number(part.bytesPerSecond || 0), 0),
        0
      )
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    },
    downloadGroups() {
      const estimationSpeed = this.currentQueueSpeed || this.lastKnownQueueSpeed
      const queueIsBlocked = this.downloadItems.some((item) =>
        (item.downloadItemParts || []).some((part) => part.waitingForNetwork || part.waitingForSpace)
      )
      let cumulativeRemainingBytes = 0
      let cumulativeRetrySeconds = 0
      let canEstimate = estimationSpeed > 0 && !queueIsBlocked
      return this.downloadItems.map((item) => {
        const parts = item.downloadItemParts || []
        const isEligible = !item.isPaused && !this.isFailedItem(item) && parts.some((part) => !part.completed)
        const remainingBytes = this.getRemainingBytes(parts)
        if (isEligible && remainingBytes === null) canEstimate = false
        if (isEligible && remainingBytes !== null) cumulativeRemainingBytes += remainingBytes
        if (isEligible) {
          const retryAfter = Math.max(...parts.filter((part) => part.waitingForRetry).map((part) => Number(part.retryAfterTime || 0)), 0)
          cumulativeRetrySeconds += Math.max(0, Math.ceil((retryAfter - this.now) / 1000))
        }
        return {
          item,
          parts,
          progress: item.itemProgress || this.getItemProgress(parts),
          status: this.getItemStatus(item, parts),
          speed: parts.reduce((total, part) => total + Number(part.bytesPerSecond || 0), 0),
          etaSeconds: isEligible && canEstimate ? Math.ceil(cumulativeRemainingBytes / estimationSpeed) + cumulativeRetrySeconds : null
        }
      })
    }
  },
  watch: {
    currentQueueSpeed(speed) {
      if (speed > 0) this.lastKnownQueueSpeed = speed
    }
  },
  methods: {
    getItemProgress(parts) {
      let totalBytes = 0
      let totalBytesDownloaded = 0
      parts.forEach((part) => {
        totalBytes += part.completed ? Number(part.bytesDownloaded) : Number(part.fileSize)
        totalBytesDownloaded += Number(part.bytesDownloaded)
      })
      if (!totalBytes) return 0
      return Math.min(1, totalBytesDownloaded / totalBytes)
    },
    getRemainingBytes(parts) {
      let remaining = 0
      for (const part of parts) {
        if (part.completed) continue
        const size = Number(part.fileSize || 0)
        if (size <= 0) return null
        remaining += Math.max(0, size - Number(part.bytesDownloaded || 0))
      }
      return remaining
    },
    isFailedItem(item) {
      return (item.downloadItemParts || []).some((part) => part.failed)
    },
    getItemStatus(item, parts) {
      if (item.isPaused) return this.$strings.MessageDownloadPaused
      if (parts.some((part) => part.failed)) return this.$strings.MessageDownloadInterrupted
      if (parts.some((part) => part.waitingForWifi)) return this.$strings.MessageWaitingForWifi
      if (parts.some((part) => part.waitingForNetwork)) return this.$strings.MessageWaitingForNetwork || 'Waiting for network'
      if (parts.some((part) => part.waitingForSpace)) return this.$strings.MessageWaitingForAvailableStorage || 'Waiting for available storage'
      const retryingPart = parts.find((part) => part.waitingForRetry)
      if (retryingPart) return this.getRetryText(retryingPart)
      if ((item.itemProgress || 0) >= 1) return this.$strings.MessageDownloadCompleteProcessing
      if (!parts.some((part) => !part.completed && (part.downloadId != null || part.isMoving))) return this.$strings.MessageDownloadQueued
      return this.$strings.MessageDownloading
    },
    getStatusText(itemPart) {
      if (itemPart.waitingForWifi) return this.$strings.MessageWaitingForWifi
      if (itemPart.waitingForNetwork) return this.$strings.MessageWaitingForNetwork || 'Waiting for network'
      if (itemPart.waitingForSpace) return this.$strings.MessageWaitingForAvailableStorage || 'Waiting for available storage'
      if (itemPart.waitingForRetry) return this.getRetryText(itemPart)
      if (itemPart.failed) return itemPart.lastError || this.$strings.MessageDownloadInterrupted
      if (!itemPart.completed && itemPart.downloadId == null && !itemPart.isMoving) return this.$strings.MessageDownloadQueued
      return ''
    },
    getRetryText(itemPart) {
      const seconds = Math.max(0, Math.ceil((Number(itemPart.retryAfterTime || 0) - this.now) / 1000))
      return this.$getString('MessageDownloadRetryingIn', [seconds])
    },
    formatSpeed(bytesPerSecond) {
      let value = Number(bytesPerSecond || 0)
      const units = ['B/s', 'KB/s', 'MB/s', 'GB/s']
      let unit = 0
      while (value >= 1024 && unit < units.length - 1) {
        value /= 1024
        unit++
      }
      const digits = unit > 0 && value < 10 ? 1 : 0
      return `${value.toFixed(digits)} ${units[unit]}`
    },
    getEtaText(seconds) {
      if (this.showAbsoluteEta) {
        const format = seconds >= 86400 ? 'MMM d, HH:mm' : 'HH:mm'
        return this.$getString('MessageDownloadEstimatedDoneAt', [this.$formatDate(this.now + seconds * 1000, format)])
      }
      return this.$getString('MessageDownloadEstimatedRemaining', [this.$elapsedPretty(seconds)])
    },
    toggleEtaDisplay() {
      this.showAbsoluteEta = !this.showAbsoluteEta
    },
    arePartsExpanded(downloadItemId) {
      return !!this.expandedDownloadItems[downloadItemId]
    },
    toggleParts(downloadItemId) {
      this.$set(this.expandedDownloadItems, downloadItemId, !this.arePartsExpanded(downloadItemId))
    },
    openItem(item) {
      const path = item.episodeId ? `/item/${item.libraryItemId}/${item.episodeId}` : `/item/${item.libraryItemId}`
      this.$router.push(path)
    },
    async onQueueReordered() {
      if (!this.canReorder) return
      const downloadItemIds = this.downloadItems.map((item) => item.id)
      const result = await AbsDownloader.reorderDownloadItems({ downloadItemIds })
      if (result?.value === false) console.error('Native download queue rejected the new order')
    },
    canPause(group) {
      return !group.item.isPaused && !this.canRetry(group) && group.progress < 1
    },
    canResume(group) {
      return !!group.item.isPaused
    },
    canRetry(group) {
      return group.parts.some((part) => part.failed)
    },
    async pauseDownload(item) {
      await AbsDownloader.pauseDownloadItem({ downloadItemId: item.id })
    },
    async pauseAllDownloads() {
      await AbsDownloader.pauseAllDownloadItems()
    },
    async resumeAllDownloads() {
      await AbsDownloader.resumeAllDownloadItems()
    },
    async continueOnCellular() {
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: this.$strings.MessageConfirmDownloadUsingCellular
      })
      if (!value) return
      await AbsDownloader.allowCellularForAllDownloadItems()
    },
    async removeAllDownloads() {
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: this.$strings.MessageRemoveAllDownloads
      })
      if (!value) return
      const result = await AbsDownloader.cancelAllDownloadItems()
      if (result?.value !== false) this.$store.commit('globals/clearItemDownloads')
    },
    async resumeDownload(item) {
      await AbsDownloader.resumeDownloadItem({ downloadItemId: item.id })
    },
    async retryDownload(item) {
      await AbsDownloader.retryDownloadItem({ downloadItemId: item.id })
    },
    async cancelDownload(item) {
      const result = await AbsDownloader.cancelDownloadItem({ downloadItemId: item.id })
      if (result?.value !== false) this.$store.commit('globals/removeItemDownload', item.id)
    },
    getStorageLocationName(id) {
      return id === 'internal' ? this.$strings.LabelInternalAppStorage : this.$strings.LabelSharedStorageStaging
    },
    async refreshStorageStats() {
      if (this.$platform !== 'android') return
      try {
        const result = await AbsDownloader.getQueueStorageStats()
        this.storageLocations = result?.locations || []
      } catch (error) {
        console.warn('Failed to load download queue storage stats', error)
      }
    }
  },
  mounted() {
    if (this.currentQueueSpeed > 0) this.lastKnownQueueSpeed = this.currentQueueSpeed
    this.refreshStorageStats()
    this.countdownTimer = setInterval(() => {
      this.now = Date.now()
      this.storageRefreshTick += 1
      if (this.storageRefreshTick % 5 === 0) this.refreshStorageStats()
    }, 1000)
  },
  beforeDestroy() {
    clearInterval(this.countdownTimer)
  }
}
</script>
