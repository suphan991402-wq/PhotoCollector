package com.photocollector.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.photocollector.app.Prefs.sentCount

/**
 * Foreground service: คอยเฝ้าดูรูปใหม่ในเครื่อง แล้วส่งเข้ากลุ่ม Telegram อัตโนมัติ
 */
class CollectorService : Service() {

    companion object {
        private const val CHANNEL_ID = "collector_channel"
        private const val NOTI_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, CollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CollectorService::class.java))
        }
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    private val debounceRunnable = Runnable { runSend() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        workerThread = HandlerThread("sync-worker").apply { start() }
        workerHandler = Handler(workerThread.looper)
        registerObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification("กำลังเฝ้าดูรูปใหม่ พร้อมส่งเข้ากลุ่ม")
        // เช็ครูปที่อาจค้างส่งตอนบริการเริ่ม
        scheduleSend(1000)
        return START_STICKY
    }

    private fun registerObserver() {
        val obs = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // มีรูปใหม่ถูกบันทึก/ดาวน์โหลด -> ตั้งเวลาส่ง (debounce)
                scheduleSend(3500)
            }
        }
        observer = obs
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
        )
    }

    private fun scheduleSend(delayMs: Long) {
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, delayMs)
    }

    private fun runSend() {
        workerHandler.post {
            val n = PhotoSync.sendPending(applicationContext) { sent, total ->
                mainHandler.post {
                    startForegroundNotification("กำลังส่ง $sent/$total เข้ากลุ่ม…")
                }
            }
            val total = applicationContext.sentCount
            mainHandler.post {
                startForegroundNotification(
                    if (n > 0) "ส่งเข้ากลุ่มแล้วทั้งหมด $total รูป"
                    else "กำลังเฝ้าดูรูปใหม่ (ส่งแล้ว $total รูป)"
                )
            }
        }
    }

    private fun startForegroundNotification(text: String) {
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ส่งรูปเข้ากลุ่ม Telegram")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "บริการส่งรูป", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "แจ้งสถานะการส่งรูปเข้ากลุ่มเบื้องหลัง"
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        mainHandler.removeCallbacks(debounceRunnable)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
