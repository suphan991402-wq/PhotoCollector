package com.photocollector.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * เรียก Telegram Bot API ตรง ๆ ด้วย HttpURLConnection (ไม่ต้องใช้ไลบรารีเสริม)
 */
class TelegramApi(private val token: String, private val chatId: String) {

    companion object {
        private const val TAG = "TelegramApi"
        private const val BASE = "https://api.telegram.org/bot"
    }

    data class Result(val ok: Boolean, val retryAfter: Int = 0, val error: String? = null)

    /** ส่งข้อความ (ใช้ทดสอบการเชื่อมต่อ) */
    fun sendMessage(text: String): Result {
        return try {
            val url = URL(
                "$BASE$token/sendMessage?chat_id=" +
                    URLEncoder.encode(chatId, "UTF-8") +
                    "&text=" + URLEncoder.encode(text, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            readResult(conn)
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage error: ${e.message}")
            Result(false, error = e.message)
        }
    }

    /**
     * ส่งไฟล์แบบเต็มคุณภาพ (ไม่บีบ) ผ่าน sendDocument (multipart/form-data)
     * caption (ถ้ามี) จะโชว์เป็นข้อความใต้ไฟล์ในแชท
     */
    fun sendDocument(filename: String, mime: String, input: InputStream, caption: String? = null): Result =
        uploadFile(
            method = "sendDocument",
            fieldName = "document",
            filename = filename,
            mime = mime,
            input = input,
            extraFields = buildList {
                add("disable_content_type_detection" to "false")
                if (!caption.isNullOrBlank()) add("caption" to caption)
            }
        )

    /**
     * ส่งเป็นรูปพรีวิว ผ่าน sendPhoto — Telegram จะบีบอัด/ย่อขนาดรูปเอง (สูงสุด ~10MB ต่อรูป)
     * โหมดนี้ไม่โชว์ชื่อไฟล์ในแชท ต้องใช้ caption ถ้าอยากให้เห็นแหล่งที่มา
     */
    fun sendPhoto(filename: String, mime: String, input: InputStream, caption: String? = null): Result =
        uploadFile(
            method = "sendPhoto",
            fieldName = "photo",
            filename = filename,
            mime = mime,
            input = input,
            extraFields = buildList {
                if (!caption.isNullOrBlank()) add("caption" to caption)
            }
        )

    private fun uploadFile(
        method: String,
        fieldName: String,
        filename: String,
        mime: String,
        input: InputStream,
        extraFields: List<Pair<String, String>>
    ): Result {
        val boundary = "----PhotoCollector" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$BASE$token/$method")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(0)

            val lineEnd = "\r\n"
            val twoHyphens = "--"

            DataOutputStream(conn.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.writeBytes(twoHyphens + boundary + lineEnd)
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                    out.writeBytes(value + lineEnd)
                }

                writeField("chat_id", chatId)
                extraFields.forEach { (name, value) -> writeField(name, value) }

                // part: ไฟล์
                out.writeBytes(twoHyphens + boundary + lineEnd)
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"" +
                        filename.replace("\"", "_") + "\"$lineEnd"
                )
                out.writeBytes("Content-Type: $mime$lineEnd$lineEnd")

                val buf = ByteArray(64 * 1024)
                input.use { ins ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
                out.writeBytes(lineEnd)
                out.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                out.flush()
            }
            readResult(conn)
        } catch (e: Exception) {
            Log.w(TAG, "$method error: ${e.message}")
            Result(false, error = e.message)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResult(conn: HttpURLConnection): Result {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
        } ?: ""

        if (code in 200..299) {
            return Result(true)
        }
        // จัดการโดน rate limit (429) -> อ่าน retry_after
        var retry = 0
        var desc: String? = null
        try {
            val json = JSONObject(body)
            desc = json.optString("description")
            val params = json.optJSONObject("parameters")
            if (params != null) retry = params.optInt("retry_after", 0)
        } catch (_: Exception) {}
        Log.w(TAG, "HTTP $code: $body")
        return Result(false, retryAfter = retry, error = desc ?: "HTTP $code")
    }
}
