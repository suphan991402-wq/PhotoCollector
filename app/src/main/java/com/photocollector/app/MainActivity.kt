package com.photocollector.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.photocollector.app.Prefs.autoEnabled
import com.photocollector.app.Prefs.devicePrefix
import com.photocollector.app.Prefs.sendAsPhoto
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

        b.etPrefix.setText(devicePrefix)

        b.switchPhotoMode.isChecked = sendAsPhoto
        b.switchPhotoMode.setOnCheckedChangeListener { _, checked ->
            sendAsPhoto = checked
        }

        b.btnSaveTest.setOnClickListener {
            if (!Prefs.hasConfig()) {
                toast("แอปยังไม่ได้ตั้งค่า Bot (ติดต่อผู้พัฒนา)")
                return@setOnClickListener
            }
            savePrefixField()
            ensurePermsThen { saveAndTest() }
        }

        b.switchAuto.isChecked = autoEnabled
        b.switchAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            if (checked) {
                if (!Prefs.hasConfig()) {
                    toast("แอปยังไม่ได้ตั้งค่า Bot (ติดต่อผู้พัฒนา)")
                    setSwitchChecked(false)
                    return@setOnCheckedChangeListener
                }
                savePrefixField()
                ensurePermsThen {
                    autoEnabled = true
                    toast("กำลังเตรียมระบบ…")
                    // ตั้ง baseline ให้เสร็จก่อน (จำรูปที่มีอยู่ทั้งหมดว่า "รู้จักแล้ว") แล้วค่อยเริ่ม service
                    // ต้องรอให้เสร็จจริง ๆ ไม่งั้น service อาจเริ่มเช็ครูปก่อน baseline เขียนเสร็จ
                    // แล้วเข้าใจผิดว่ารูปเก่าทั้งหมดเป็นรูปใหม่ ส่งรัวออกไปทั้งเครื่อง
                    io.execute {
                        PhotoSync.markAllExistingAsKnown(applicationContext)
                        runOnUiThread {
                            CollectorService.start(this)
                            toast("เปิดส่งอัตโนมัติแล้ว (เฉพาะรูปใหม่หลังจากนี้)")
                            setSwitchChecked(true)
                        }
                    }
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
            if (!Prefs.hasConfig()) {
                toast("แอปยังไม่ได้ตั้งค่า Bot (ติดต่อผู้พัฒนา)")
                return@setOnClickListener
            }
            savePrefixField()
            ensurePermsThen { sendAllExisting() }
        }

        b.tvVersion.text = "v${BuildConfig.VERSION_CODE}"
        b.btnCheckUpdate.setOnClickListener { checkForUpdate(manual = true) }
        checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        setSwitchChecked(autoEnabled)
        refreshStatus()
    }

    private fun refreshStatus() {
        b.tvStatus.text = if (Prefs.hasConfig())
            "✅ ตั้งค่าบอทแล้ว พร้อมส่งเข้ากลุ่ม"
        else
            "⚠️ แอปยังไม่ได้ตั้งค่า Bot (ติดต่อผู้พัฒนา)"
        b.tvCount.text = "ส่งเข้ากลุ่มไปแล้วทั้งหมด: ${sentCount} รูป"
    }

    private fun savePrefixField() {
        devicePrefix = b.etPrefix.text.toString().trim()
    }

    private fun saveAndTest() {
        toast("กำลังทดสอบการเชื่อมต่อ…")
        val prefix = devicePrefix
        val label = if (prefix.isNotEmpty()) "เครื่อง \"$prefix\"" else "เครื่องนี้ (ยังไม่ได้ตั้งชื่อเครื่อง/Prefix)"
        io.execute {
            val msgRes = PhotoSync.sendTestMessageToAll("✅ เชื่อมต่อสำเร็จ — $label พร้อมส่งเข้ากลุ่มนี้แล้ว")
            if (!msgRes.ok) {
                runOnUiThread {
                    refreshStatus()
                    toast("ไม่สำเร็จ: ${msgRes.error ?: "ตรวจ token/chat id อีกครั้ง"}")
                }
                return@execute
            }
            // ทดสอบส่งรูปล่าสุดในเครื่องด้วย เพื่อเช็ค pipeline ส่งรูปจริง ไม่ใช่แค่ข้อความ
            val photoRes = PhotoSync.sendLatestPhoto(applicationContext)
            runOnUiThread {
                refreshStatus()
                val extra = when {
                    photoRes == null -> " (ไม่พบรูปในเครื่องให้ทดสอบส่ง)"
                    photoRes.ok -> " + ส่งรูปล่าสุดในเครื่องสำเร็จ"
                    else -> " (ส่งรูปทดสอบไม่สำเร็จ: ${photoRes.error})"
                }
                toast("สำเร็จ! เช็คในกลุ่ม Telegram ได้เลย$extra")
            }
        }
    }

    private fun sendAllExisting() {
        toast("กำลังส่งรูปเก่าทั้งหมด… อาจใช้เวลาสักครู่")
        io.execute {
            // ล้างประวัติ เพื่อส่งรูปที่มีอยู่ทั้งหมดในเครื่อง
            Prefs.clearKnown(applicationContext)
            val result = PhotoSync.sendPending(applicationContext) { sent, total ->
                runOnUiThread { b.tvStatus.text = "กำลังส่ง $sent/$total …" }
            }
            runOnUiThread {
                refreshStatus()
                toast(
                    when {
                        result.sent > 0 -> "ส่งรูปเก่าเข้ากลุ่ม ${result.sent} รูปแล้ว"
                        result.pendingCount == 0 -> "ไม่พบรูปในเครื่องเลย"
                        else -> "ส่งไม่สำเร็จ: ${result.error ?: "ไม่ทราบสาเหตุ"}"
                    }
                )
            }
        }
    }

    // ---- อัพเดทแอป ----

    /** เช็คเวอร์ชันล่าสุดจาก GitHub — manual=true คือกดปุ่มเอง (แจ้งผลทุกกรณี), false คือเช็คเงียบ ๆ ตอนเปิดแอป */
    private fun checkForUpdate(manual: Boolean) {
        if (manual) toast("กำลังเช็คอัพเดท…")
        io.execute {
            val latest = UpdateChecker.fetchLatest()
            runOnUiThread {
                when {
                    latest == null -> if (manual) toast("เช็คอัพเดทไม่สำเร็จ (ต้องต่อเน็ต)")
                    latest.versionCode > BuildConfig.VERSION_CODE -> showUpdateDialog(latest)
                    manual -> toast("เป็นเวอร์ชันล่าสุดแล้ว (v${BuildConfig.VERSION_CODE})")
                }
            }
        }
    }

    private fun showUpdateDialog(latest: UpdateChecker.LatestRelease) {
        AlertDialog.Builder(this)
            .setTitle("มีเวอร์ชันใหม่ ${latest.tag}")
            .setMessage("ตอนนี้ใช้ v${BuildConfig.VERSION_CODE} อยู่ ต้องการอัพเดทเลยไหม?")
            .setPositiveButton("อัพเดทเลย") { _, _ -> downloadAndInstall(latest) }
            .setNegativeButton("ไว้ทีหลัง", null)
            .show()
    }

    private fun downloadAndInstall(latest: UpdateChecker.LatestRelease) {
        toast("กำลังดาวน์โหลดอัพเดท…")
        io.execute {
            val uri = UpdateChecker.download(applicationContext, latest) { percent ->
                runOnUiThread { b.tvStatus.text = "กำลังดาวน์โหลดอัพเดท $percent%…" }
            }
            runOnUiThread {
                refreshStatus()
                if (uri != null) {
                    startActivity(UpdateChecker.installIntent(uri))
                } else {
                    toast("ดาวน์โหลดอัพเดทไม่สำเร็จ ลองใหม่อีกครั้ง")
                }
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
