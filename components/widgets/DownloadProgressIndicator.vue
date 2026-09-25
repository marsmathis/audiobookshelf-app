<template>
  <div v-if="downloadItemPartsRemaining.length" @click="clickedIt">
    <widgets-circle-progress :value="progress" :count="downloadItemPartsRemaining.length" />
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      downloadItemListener: null,
      completeListener: null,
      itemPartUpdateListener: null,
      queueChangedListener: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    },
    downloadItemPartsRemaining() {
      return this.downloadItemParts.filter((dip) => !dip.completed)
    },
    progress() {
      let totalBytes = 0
      let totalBytesDownloaded = 0
      this.downloadItemParts.forEach((dip) => {
        totalBytes += dip.fileSize
        totalBytesDownloaded += dip.bytesDownloaded
      })

      if (!totalBytes) return 0
      return Math.min(1, totalBytesDownloaded / totalBytes)
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item download complete payload')
        return
      }

      if (!data.localLibraryItem) {
        this.$toast.error(this.$strings.MessageItemDownloadCompleteFailedToCreate)
      } else {
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      }

      if (data.localMediaProgress) {
        console.log('onItemDownloadComplete updating local media progress', data.localMediaProgress.id)
        this.$store.commit('globals/updateLocalMediaProgress', data.localMediaProgress)
      }

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
    },
    onDownloadItem(downloadItem) {
      console.log('DownloadProgressIndicator onDownloadItem', JSON.stringify(downloadItem))

      let totalBytes = 0
      let downloadedBytes = 0
      ;(downloadItem.downloadItemParts || []).forEach((part) => {
        totalBytes += part.completed ? Number(part.bytesDownloaded || 0) : Number(part.fileSize || 0)
        downloadedBytes += Number(part.bytesDownloaded || 0)
      })
      downloadItem.itemProgress = totalBytes > 0 ? Math.min(1, downloadedBytes / totalBytes) : 0
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
    },
    onQueueChanged(data) {
      console.log('DownloadProgressIndicator onQueueChanged', JSON.stringify(data))
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.queueChangedListener = await AbsDownloader.addListener('onQueueChanged', (data) => this.onQueueChanged(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
    try {
      const result = await AbsDownloader.getDownloadItems()
      ;(result?.items || []).forEach((item) => this.onDownloadItem(item))
    } catch (error) {
      console.warn('Failed to restore download queue in UI', error)
    }
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
    this.queueChangedListener?.remove()
  }
}
</script>
