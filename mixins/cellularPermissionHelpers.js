import { Dialog } from '@capacitor/dialog';

export default {
  data() {
    return {
      downloadAllowsCellular: false
    }
  },
  methods: {
    async checkCellularPermission(actionType) {
      if (actionType === 'download') this.downloadAllowsCellular = false
      if (this.$store.state.networkConnectionType !== 'cellular') return true

      let permission;
      if (actionType === 'download') {
        permission = this.$store.getters['getCanDownloadUsingCellular']
        if (permission === 'NEVER') {
          this.$toast.error(this.$strings.ToastDownloadNotAllowedOnCellular)
          return false
        }
      } else if (actionType === 'streaming') {
        permission = this.$store.getters['getCanStreamingUsingCellular']
        if (permission === 'NEVER') {
          this.$toast.error(this.$strings.ToastStreamingNotAllowedOnCellular)
          return false
        }
      }

      if (permission === 'ASK') {
        const confirmed = await this.confirmAction(actionType)
        if (actionType === 'download') this.downloadAllowsCellular = confirmed
        return confirmed
      }

      if (actionType === 'download') this.downloadAllowsCellular = permission === 'ALWAYS'

      return true
    },
    getDownloadAllowsCellular() {
      return this.downloadAllowsCellular || this.$store.getters['getCanDownloadUsingCellular'] === 'ALWAYS'
    },
    async confirmAction(actionType) {
      const message = actionType === 'download' ?
        this.$strings.MessageConfirmDownloadUsingCellular :
        this.$strings.MessageConfirmStreamingUsingCellular

      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message
      })
      return value
    }
  }
}
