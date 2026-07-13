package com.photocollector.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.photocollector.app.Prefs.autoEnabled
import java.util.concurrent.TimeUnit

/**
 * เช็คเป็นระยะว่าโหมดอัตโนมัติควรทำงานอยู่ไหม — ถ้าใช่ สั่ง service เริ่มใหม่เสมอ
 * (ไม่มีผลถ้ายังทำงานอยู่แล้ว) เผื่อระบบเครื่องฆ่า service ทิ้งไปเงียบ ๆ
 * แล้วกวาดรูปที่อาจค้างส่งไปด้วย เผื่อ ContentObserver พลาด event
 * WorkManager รับประกันว่างานนี้จะรันอยู่เรื่อย ๆ ทนต่อการถูกฆ่ามากกว่า service เปล่า ๆ
 */
class HealthCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "HealthCheckWorker"
        private const val WORK_NAME = "photo_collector_health_check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!ctx.autoEnabled) return Result.success()
        Log.d(TAG, "health check: ensuring service is running + sweeping pending photos")
        CollectorService.start(ctx)
        PhotoSync.sendPending(ctx)
        return Result.success()
    }
}
