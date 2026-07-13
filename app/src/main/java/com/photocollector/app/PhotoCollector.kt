package com.photocollector.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.photocollector.app.Prefs.addSent
import com.photocollector.app.Prefs.devicePrefix
import com.photocollector.app.Prefs.sendAsPhoto
import java.util.concurrent.atomic.AtomicBoolean

/**
 * อ่านรูปทั้งเครื่องผ่าน MediaStore แล้วส่งเข้ากลุ่ม Telegram (กันส่งซ้ำ)
 */
object PhotoSync {

    private const val TAG = "PhotoSync"
    private const val DELAY_BETWEEN_MS = 1200L   // กัน rate limit ของ Telegram
    private const val MAX_SEND_PHOTO_BYTES = 10L * 1024 * 1024   // ข้อจำกัดของ Telegram sendPhoto
    private val running = AtomicBoolean(false)

    data class MediaItem(
        val id: Long,
        val name: String,
        val size: Long,
        val mime: String,
        val uri: Uri,
        val key: String
    )

    /** ดึงรายการรูปทั้งหมดในเครื่องจาก MediaStore */
    fun queryImages(context: Context): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sort = MediaStore.Images.Media.DATE_MODIFIED + " ASC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "photo_$id.jpg"
                val size = c.getLong(sizeCol)
                val mime = c.getString(mimeCol) ?: "image/jpeg"
                val date = c.getLong(dateCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                items.add(MediaItem(id, name, size, mime, uri, "$id:$date"))
            }
        }
        return items
    }

    /**
     * ตั้ง baseline: ทำเครื่องหมายว่ารูปที่มีอยู่ตอนนี้ "รู้จักแล้ว" โดยไม่ส่ง
     * ใช้ตอนเปิดโหมดอัตโนมัติ เพื่อไม่ให้สแปมรูปเก่าทั้งหมดเข้ากลุ่ม
     */
    fun markAllExistingAsKnown(context: Context) {
        val keys = queryImages(context).map { it.key }
        Prefs.addKnown(context, keys)
        Log.d(TAG, "baseline set: ${keys.size} existing photos marked known")
    }

    /** ผลของการซิงก์หนึ่งรอบ — error ไม่ null เมื่อมีรูปรอส่งแต่ส่งไม่สำเร็จ (ใช้ debug ว่าทำไมไม่เข้ากลุ่ม) */
    data class SyncResult(val sent: Int, val pendingCount: Int, val error: String? = null)

    /**
     * ส่งรูปที่ยังไม่เคยส่ง (ไม่อยู่ใน known) เข้ากลุ่ม Telegram
     */
    fun sendPending(context: Context, onProgress: ((sent: Int, total: Int) -> Unit)? = null): SyncResult {
        if (!Prefs.hasConfig()) {
            Log.w(TAG, "no token/chatId configured (BuildConfig empty)")
            return SyncResult(0, 0, "แอปยังไม่ได้ตั้งค่า Bot")
        }
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "already running, skip")
            return SyncResult(0, 0, null)
        }
        try {
            val api = TelegramApi(BuildConfig.BOT_TOKEN, BuildConfig.CHAT_ID)
            val known = Prefs.knownIds(context)
            val pending = queryImages(context).filter { it.key !in known }
            if (pending.isEmpty()) return SyncResult(0, 0, null)

            var sent = 0
            var lastError: String? = null
            for ((index, item) in pending.withIndex()) {
                val res = sendOne(context, api, item)
                if (res.ok) {
                    sent++
                    Prefs.addKnown(context, listOf(item.key))
                    onProgress?.invoke(sent, pending.size)
                } else {
                    // ส่งไม่สำเร็จ (เช่น เน็ตหลุด) หยุดรอบนี้ ไว้ลองใหม่รอบหน้า
                    lastError = res.error
                    Log.w(TAG, "stop at index $index due to failure: ${res.error}")
                    break
                }
                if (index < pending.size - 1) {
                    try { Thread.sleep(DELAY_BETWEEN_MS) } catch (_: InterruptedException) {}
                }
            }
            if (sent > 0) addSent(context, sent)
            Log.d(TAG, "sent $sent / ${pending.size}")
            return SyncResult(sent, pending.size, lastError)
        } finally {
            running.set(false)
        }
    }

    /** เติม prefix ชื่อเครื่องหน้าไฟล์ (ถ้าตั้งไว้) เช่น "แม่_IMG_0012.jpg" */
    private fun prefixedName(context: Context, name: String): String {
        val prefix = context.devicePrefix
        return if (prefix.isEmpty()) name else "${prefix}_$name"
    }

    /** ส่งไฟล์เดียว พร้อม retry หนึ่งครั้งเมื่อโดน rate limit */
    private fun sendOne(context: Context, api: TelegramApi, item: MediaItem): TelegramApi.Result {
        val outName = prefixedName(context, item.name)
        repeat(2) { attempt ->
            val input = try {
                context.contentResolver.openInputStream(item.uri)
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${item.name}: ${e.message}")
                return TelegramApi.Result(false, error = "เปิดไฟล์รูปไม่ได้: ${e.message}")
            } ?: return TelegramApi.Result(false, error = "เปิดไฟล์รูปไม่ได้ (${item.name})")

            // ส่งเป็นรูปพรีวิว (บีบอัด) ถ้าเปิดตัวเลือกไว้และไฟล์ไม่เกินขีดจำกัดของ sendPhoto
            // ไม่งั้นส่งแบบไฟล์คุณภาพเต็มตามปกติ
            val res = if (context.sendAsPhoto && item.size in 1..MAX_SEND_PHOTO_BYTES)
                api.sendPhoto(outName, item.mime, input)
            else
                api.sendDocument(outName, item.mime, input)
            if (res.ok) return res

            if (res.retryAfter > 0 && attempt == 0) {
                val waitMs = (res.retryAfter + 1) * 1000L
                Log.d(TAG, "rate limited, wait ${waitMs}ms")
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) {}
                // วนไปลองใหม่อีกครั้ง
            } else {
                return res
            }
        }
        return TelegramApi.Result(false, error = "ลองส่งซ้ำแล้วไม่สำเร็จ")
    }
}
