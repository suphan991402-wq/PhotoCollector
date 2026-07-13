package com.photocollector.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * เช็คเวอร์ชันล่าสุดจาก GitHub Releases ของ repo นี้ ดาวน์โหลด apk แล้วเปิดตัวติดตั้งให้
 * ทำงานได้เพราะ repo เป็น public — ไม่ต้องใช้ token ใด ๆ ในการเช็ค/โหลด
 * แท็ก release ต้องตั้งชื่อแบบ v<versionCode> เช่น v3 (ตรงกับ defaultConfig.versionCode)
 */
object UpdateChecker {
    private const val API_URL =
        "https://api.github.com/repos/suphan991402-wq/PhotoCollector/releases/latest"

    data class LatestRelease(val versionCode: Int, val tag: String, val apkUrl: String)

    /** เช็คเวอร์ชันล่าสุด คืน null ถ้าเช็คไม่ได้ หรือไม่มี apk แนบมากับ release */
    fun fetchLatest(): LatestRelease? {
        return try {
            // ใส่ timestamp กัน cache ทุกชั้น (โปรแกรม/OS/proxy) ตอบข้อมูลเวอร์ชันเก่า
            val conn = URL("$API_URL?_=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PhotoCollector-App")
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val versionCode = tag.removePrefix("v").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            apkUrl?.let { LatestRelease(versionCode, tag, it) }
        } catch (_: Exception) {
            null
        }
    }

    /** ดาวน์โหลด apk ของเวอร์ชันที่ระบุมาเก็บใน cache แล้วคืน content:// uri สำหรับเปิดตัวติดตั้ง */
    fun download(context: Context, latest: LatestRelease, onProgress: ((percent: Int) -> Unit)? = null): Uri? {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // ลบไฟล์อัพเดทเก่าที่ค้างอยู่ทั้งหมดก่อน กันหยิบไฟล์เวอร์ชันเก่ามาติดตั้งผิด
            dir.listFiles()?.forEach { it.delete() }
            // ใส่เลขเวอร์ชันในชื่อไฟล์ กันระบบ/ตัวติดตั้งเอาไฟล์ชื่อเดิมที่เคย cache ไว้มาใช้
            val file = File(dir, "update-${latest.tag}.apk")
            val conn = URL(latest.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            val total = conn.contentLength
            conn.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        output.write(buf, 0, r)
                        downloaded += r
                        if (total > 0) onProgress?.invoke(downloaded * 100 / total)
                    }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    /** Intent เปิดตัวติดตั้งของระบบ Android (ผู้ใช้ยังต้องกด "ติดตั้ง" เองหนึ่งครั้ง ตามข้อกำหนดความปลอดภัยของ Android) */
    fun installIntent(apkUri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
}
