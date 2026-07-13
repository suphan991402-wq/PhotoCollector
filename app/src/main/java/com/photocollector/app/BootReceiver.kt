package com.photocollector.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photocollector.app.Prefs.autoEnabled

/** เปิดบริการเก็บรูปอัตโนมัติอีกครั้งหลังรีสตาร์ทเครื่อง */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (context.autoEnabled) {
                CollectorService.start(context)
                HealthCheckWorker.schedule(context)
            }
        }
    }
}
