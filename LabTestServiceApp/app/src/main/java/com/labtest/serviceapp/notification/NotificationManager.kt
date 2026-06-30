package com.labtest.serviceapp.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.labtest.serviceapp.MainActivity

object NotificationManager {

    const val CHANNEL_PROGRESS = "labtest_progress"
    const val CHANNEL_COMPLETE = "labtest_complete"
    const val CHANNEL_ERROR = "labtest_error"

    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Operation progress" },
                NotificationChannel(CHANNEL_COMPLETE, "Complete", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Operation complete" },
                NotificationChannel(CHANNEL_ERROR, "Error", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Operation error" }
            )
            channels.forEach { getManager().createNotificationChannel(it) }
        }
    }

    private fun getManager(): NotificationManager {
        return ctx!!.getSystemService(NotificationManager::class.java)
    }

    private fun getPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(ctx!!, 0, Intent(ctx!!, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showProgress(id: Int, title: String, message: String, progress: Int, max: Int = 100) {
        val notification = NotificationCompat.Builder(ctx!!, CHANNEL_PROGRESS)
            .setContentTitle(title).setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(getPendingIntent())
            .setProgress(max, progress, progress == 0)
            .setOngoing(true).build()
        getManager().notify(id, notification)
    }

    fun showComplete(id: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(ctx!!, CHANNEL_COMPLETE)
            .setContentTitle(title).setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(getPendingIntent())
            .setAutoCancel(true).build()
        getManager().notify(id, notification)
    }

    fun showError(id: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(ctx!!, CHANNEL_ERROR)
            .setContentTitle(title).setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(getPendingIntent())
            .setAutoCancel(true).build()
        getManager().notify(id, notification)
    }

    fun clearProgress(id: Int) {
        getManager().cancel(id)
    }

    fun flashProgress(id: Int, current: Int, total: Int) {
        val percent = (current * 100) / total
        showProgress(id, "Flashing", "Writing $current/$total", percent)
    }

    fun flashComplete(id: Int) {
        showComplete(id, "Flash Complete", "Flash operation successful")
    }

    fun flashError(id: Int, error: String) {
        showError(id, "Flash Failed", error)
    }

    fun readProgress(id: Int, current: Int, total: Int) {
        val percent = (current * 100) / total
        showProgress(id, "Reading", "Reading $current/$total", percent)
    }

    fun eraseProgress(id: Int, current: Int, total: Int) {
        val percent = (current * 100) / total
        showProgress(id, "Erasing", "Erasing $current/$total", percent)
    }
}