<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
      <div class="w-full flex justify-center md:block sm:w-32 md:w-52" style="min-width: 240px">
        <div class="relative" style="height: fit-content">
          <covers-collection-cover :book-items="bookItems" :width="240" :height="120 * bookCoverAspectRatio" :book-cover-aspect-ratio="bookCoverAspectRatio" />
        </div>
      </div>
      <div class="flex-grow py-6">
        <div class="flex items-center px-2">
          <h1 class="text-xl font-sans">
            {{ collectionName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" color="success" :padding-x="4" :loading="playerIsStartingForThisMedia" small class="flex items-center justify-center mx-1 w-24" @click="playClick">
            <span class="material-symbols text-2xl fill">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : $strings.ButtonPlay }}</span>
          </ui-btn>
          <ui-btn v-if="localBookItems.length || queuedCollectionDownloads.length" color="error" :padding-x="3" small class="flex items-center justify-center mx-1" @click="deleteCollectionLocalFiles">
            <span class="material-symbols text-xl">delete_sweep</span>
            <span class="px-1 text-sm">{{ $strings.ButtonDeleteAllLocalFiles }}</span>
          </ui-btn>
        </div>

        <div class="my-8 max-w-2xl px-2">
          <p class="text-base text-fg">{{ description }}</p>
        </div>

        <tables-collection-books-table :books="bookItems" :collection-id="collection.id" />
      </div>
    </div>
    <div v-show="processingRemove" class="absolute top-0 left-0 w-full h-full z-10 bg-black bg-opacity-40 flex items-center justify-center">
      <ui-loading-indicator />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader, AbsFileSystem } from '@/plugins/capacitor'

export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    var collection = await app.$nativeHttp.get(`/api/collections/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!collection) {
      return redirect('/bookshelf')
    }

    // Lookup matching local items and attach to collection items
    if (collection.books.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems('book')) || []
      if (localLibraryItems.length) {
        collection.books.forEach((collectionItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === collectionItem.id)
          if (!matchingLocalLibraryItem) return
          collectionItem.localLibraryItem = matchingLocalLibraryItem
        })
      }
    }

    return {
      collection
    }
  },
  data() {
    return {
      mediaIdStartingPlayback: null,
      processingRemove: false
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookItems() {
      return this.collection.books || []
    },
    localBookItems() {
      return this.bookItems.map((book) => book.localLibraryItem).filter(Boolean)
    },
    queuedCollectionDownloads() {
      const bookIds = new Set(this.bookItems.map((book) => book.id))
      return this.$store.state.globals.itemDownloads.filter((item) => bookIds.has(item.libraryItemId))
    },
    collectionName() {
      return this.collection.name || ''
    },
    description() {
      return this.collection.description || ''
    },
    playableItems() {
      return this.bookItems.filter((book) => {
        return !book.isMissing && !book.isInvalid && book.media.tracks.length
      })
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isOpenInPlayer
    },
    isOpenInPlayer() {
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.id)
      })
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      if (!this.mediaIdStartingPlayback) return false
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      return mediaId === this.mediaIdStartingPlayback
    },
    showPlayButton() {
      return this.playableItems.length
    }
  },
  methods: {
    newLocalLibraryItem(localLibraryItem) {
      const book = this.bookItems.find((item) => item.id === localLibraryItem.libraryItemId)
      if (book) this.$set(book, 'localLibraryItem', localLibraryItem)
    },
    async deleteCollectionLocalFiles() {
      if (this.processingRemove || (!this.localBookItems.length && !this.queuedCollectionDownloads.length)) return
      const localItems = [...this.localBookItems]
      const queuedItems = [...this.queuedCollectionDownloads]
      const affectedItemIds = new Set([...localItems.map((item) => item.libraryItemId), ...queuedItems.map((item) => item.libraryItemId)])
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: this.$getString('MessageConfirmDeleteAllLocalFiles', [this.collectionName, affectedItemIds.size])
      })
      if (!value) return

      this.processingRemove = true
      let deleted = 0
      let failed = 0
      try {
        for (const queuedItem of queuedItems) {
          const result = await AbsDownloader.cancelDownloadItem({ downloadItemId: queuedItem.id })
          if (result?.value !== false) this.$store.commit('globals/removeItemDownload', queuedItem.id)
          else failed++
        }
        for (const localItem of localItems) {
          const result = await AbsFileSystem.deleteItem(localItem)
          if (result?.success) {
            const book = this.bookItems.find((item) => item.localLibraryItem?.id === localItem.id)
            if (book) this.$delete(book, 'localLibraryItem')
            deleted++
          } else failed++
        }
      } finally {
        this.processingRemove = false
      }
      const removed = affectedItemIds.size - failed
      if (removed) this.$toast.success(this.$getString('MessageLocalFilesDeleted', [removed]))
      if (failed) this.$toast.error(this.$getString('MessageLocalFilesDeleteFailed', [failed]))
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()

      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.playNextItem()
      }
    },
    playNextItem() {
      const nextBookNotRead = this.playableItems.find((pb) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](pb.id)
        return !prog?.isFinished
      })
      if (nextBookNotRead) {
        this.mediaIdStartingPlayback = nextBookNotRead.id
        this.$store.commit('setPlayerIsStartingPlayback', nextBookNotRead.id)

        if (nextBookNotRead.localLibraryItem) {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.localLibraryItem.id, serverLibraryItemId: nextBookNotRead.id })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.id })
        }
      }
    },
    libraryChanged(libraryId) {
      // A collection belongs to a single library, so leave this page when a different library is
      // selected rather than showing a collection that is not in the current library
      if (!libraryId || libraryId !== this.collection.libraryId) {
        this.$router.replace('/bookshelf/collections')
      }
    }
  },
  mounted() {
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
  }
}
</script>
