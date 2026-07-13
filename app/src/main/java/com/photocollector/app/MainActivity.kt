package com.photocollector.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.photocollector.app.Prefs.autoEnabled
import com.photocollector.app.Prefs.botToken
import com.photocollector.app.Prefs.chatId
import com.photocollector.app.Prefs.sentCount
import com.photocollector.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private var pendingAction: (() -> Unit)? = null
    private var suppressSwitchEvents = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readOk = result.entries.any {
            (it.key == Manifest.permission.READ_MEDIA_IMAGES ||
                it.key == Manifest.permission.READ_EXTERNAL_STORAGE) && it.value
        } || hasReadPermission()
        if (readOk) {
            pendingAction?.invoke()
        } else {
            toast("ต้องอนุญาตให้เข้าถึงรูปภาพก่อนถึงจะส่งได้")
        }
        setSwitchChecked(autoEnabled)
        pendingAction = null
    }

    /** ตั้งค่าสวิตช์โดยไม่ยิง listener ซ้ำ (กันการ re-entrant เมื่อโปรแกรมเปลี่ยนค่าเอง) */
    private fun setSwitchChecked(checked: Boolean) {
        if (b.switchAuto.isChecked == checked) return
        suppressSwitchEvents = true
        b.switchAuto.isChecked = checked
        suppressSwitchEvents = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.etToken.setText(botToken)
        b.etChatId.setText(chatId)

        b.btnSaveTest.setOnClickListener { saveAndTest() }

        b.switchAuto.isChecked = autoEnabled
        b.switchAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            if (checked) {
                if (!saveConfigFromFields()) {
                    setSwitchChecked(false)
                    return@setOnCheckedChangeListener
                }
                ensurePermsThen {
                    autoEnabled = true
                    // ตั้ง baseline: ไม่ส่งรูปเก่าที่มีอยู่ ส่งเฉพาะรูปใหม่หลังจากนี้
                    io.execute { PhotoSync.markAllExistingAsKnown(applicationContext) }
                    CollectorService.start(this)
                    toast("เปิดส่งอัตโนมัติแล้ว (เฉพาะรูปใหม่หลังจากนี้)")
                    setSwitchChecked(true)
                }
                // ถ้ายังไม่ได้สิทธิ์ ให้ปิดสวิตช์ไว้ก่อน เดี๋ยว callback จะเปิดให้เอง
                if (!hasReadPermission()) setSwitchChecked(false)
            } else {
                autoEnabled = false
                CollectorService.stop(this)
                toast("ปิดส่งอัตโนมัติแล้ว")
            }
        }

        b.btnSendAll.setOnClickListener {
            if (!saveConfigFromFields()) return@setOnClickListener
            ensurePermsThen { sendAllExisting() }
        }
    }

    override fun onResume() {
        super.onResume()
        setSwitchChecked(autoEnabled)
        refreshStatus()
    }

    private fun refreshStatus() {
        val configured = botToken.isNotEmpty() && chatId.isNotEmpty()
        b.tvStatus.text = if (configured)
            "✅ ตั้งค่าบอทแล้ว พร้อมส่งเข้ากลุ่ม"
        else
            "⚠️ ยังไม่ได้ใส่ Bot Token และ Chat ID"
        b.tvCount.text = "ส่งเข้ากลุ่มไปแล้วทั้งหมด: ${sentCount} รูป"
    }

    private fun saveConfigFromFields(): Boolean {
        val t = b.etToken.text.toString().trim()
        val c = b.etChatId.text.toString().trim()
        if (t.isEmpty() || c.isEmpty()) {
            toast("กรุณาใส่ Bot Token และ Chat ID ให้ครบ")
            return false
        }
        botToken = t
        chatId = c
        return true
    }

    private fun saveAndTest() {
        if (!saveConfigFromFields()) return
        toast("กำลังทดสอบการเชื่อมต่อ…")
        val api = TelegramApi(botToken, chatId)
        io.execute {
            val res = api.sendMessage("✅ เชื่อมต่อสำเร็จ — แอปรวมรูปพร้อมส่งเข้ากลุ่มนี้แล้ว")
            runOnUiThread {
                refreshStatus()
                if (res.ok) toast("สำเร็จ! เช็คในกลุ่ม Telegram ได้เลย")
                else toast("ไม่สำเร็จ: ${res.error ?: "ตรวจ token/chat id อีกครั้ง"}")
            }
        }
    }

    private fun sendAllExisting() {
        toast("กำลังส่งรูปเก่าทั้งหมด… อาจใช้เวลาสักครู่")
        io.execute {
            // ล้างประวัติ เพื่อส่งรูปที่มีอยู่ทั้งหมดในเครื่อง
            Prefs.clearKnown(applicationContext)
            val n = PhotoSync.sendPending(applicationContext) { sent, total ->
                runOnUiThread { b.tvStatus.text = "กำลังส่ง $sent/$total …" }
            }
            runOnUiThread {
                refreshStatus()
                toast(if (n > 0) "ส่งรูปเก่าเข้ากลุ่ม $n รูปแล้ว" else "ไม่พบรูปให้ส่ง หรือส่งไม่สำเร็จ")
            }
        }
    }

    // ---- สิทธิ์ ----

    private fun requiredPerms(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasReadPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermsThen(action: () -> Unit) {
        if (hasReadPermission()) {
            action()
        } else {
            pendingAction = action
            permLauncher.launch(requiredPerms())
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
