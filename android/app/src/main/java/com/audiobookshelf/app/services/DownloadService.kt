package com.audiobookshelf.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.audiobookshelf.app.R
import com.audiobookshelf.app.models.DownloadItemPart

/** Android-owned foreground lifecycle for transfers that must outlive the WebView and Activity. */
class DownloadService : Service() {
  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
    DownloadServiceHost.attachService(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> DownloadServiceHost.cancelAll(this)
      ACTION_PAUSE -> DownloadServiceHost.pauseAll(this)
      ACTION_RESUME -> DownloadServiceHost.resumeAll(this)
      else -> {
        startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
        DownloadServiceHost.startWork(this)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    DownloadServiceHost.detachService(this)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  fun onPartUpdate(part: DownloadItemPart) {
    val strings = DownloadServiceHost.notificationStrings(this)
    val text =
            if (part.waitingForWifi) strings.waitingForWifi
            else if (part.waitingForNetwork) strings.waitingForNetwork
            else if (part.waitingForSpace) strings.waitingForStorage
            else strings.downloadingFile.replace("{0}", part.filename)
    val progress = part.progress.coerceIn(0L, 100L).toInt()
    val notification = notification(text, progress, part.fileSize > 0L)
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
  }

  fun onQueueChanged(hasWork: Boolean, hasPausedItems: Boolean) {
    if (!hasWork && !hasPausedItems) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    } else if (!hasWork) {
      val strings = DownloadServiceHost.notificationStrings(this)
      val queueProgress = DownloadServiceHost.ensure(this).getQueueProgress()
      (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
              .notify(NOTIFICATION_ID, notification(strings.paused, queueProgress.progress, queueProgress.determinate))
    }
  }

  private fun startForegroundWithType(text: String) {
    val notification = notification(text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun notification(text: String, progress: Int = 0, determinate: Boolean = false): Notification {
    val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), pendingIntentFlags())
    val manager = DownloadServiceHost.ensure(this)
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(DownloadServiceHost.notificationStrings(this).downloads)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, !determinate)
            .addAction(0, DownloadServiceHost.notificationStrings(this).cancel, cancelIntent)
    if (manager.hasPausableItems()) {
      val pauseIntent = PendingIntent.getService(
              this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE), pendingIntentFlags())
      builder.addAction(0, DownloadServiceHost.notificationStrings(this).pause, pauseIntent)
    } else if (manager.hasPausedItems()) {
      val resumeIntent = PendingIntent.getService(
              this, 3, Intent(this, DownloadService::class.java).setAction(ACTION_RESUME), pendingIntentFlags())
      builder.addAction(0, DownloadServiceHost.notificationStrings(this).resume, resumeIntent)
    }
    return builder.build()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    DownloadServiceHost.notificationStrings(this).downloads,
                    NotificationManager.IMPORTANCE_LOW))
  }

  private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  companion object {
    private const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 11
    private const val ACTION_CANCEL = "com.audiobookshelf.app.download.CANCEL"
    private const val ACTION_PAUSE = "com.audiobookshelf.app.download.PAUSE"
    private const val ACTION_RESUME = "com.audiobookshelf.app.download.RESUME"
    fun intent(context: Context) = Intent(context, DownloadService::class.java)
  }
}
