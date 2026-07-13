package com.photocollector.app

import android.content.Context

/** ตัวเก็บค่าตั้งค่า (SharedPreferences) */
object Prefs {
    private const val FILE = "photocollector_prefs"
    private const val KEY_ENABLED = "auto_enabled"
    private const val KEY_PREFIX = "device_prefix"
    private const val KEY_SEND_AS_PHOTO = "send_as_photo"
    private const val KEY_COUNT = "sent_count"
    private const val KEY_KNOWN = "known_ids"
    private const val KEY_LAST_SUCCESS = "last_send_success_at"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** เปิด/ปิดโหมดส่งอัตโนมัติเบื้องหลัง */
    var Context.autoEnabled: Boolean
        get() = sp(this).getBoolean(KEY_ENABLED, false)
        set(v) { sp(this).edit().putBoolean(KEY_ENABLED, v).apply() }

    /** ชื่อเครื่อง/ป้ายกำกับ (ไม่บังคับ) — เติมหน้าชื่อไฟล์ตอนส่ง เพื่อรู้ว่ารูปมาจากเครื่องไหน */
    var Context.devicePrefix: String
        get() = sp(this).getString(KEY_PREFIX, "") ?: ""
        set(v) { sp(this).edit().putString(KEY_PREFIX, v.trim()).apply() }

    /** ส่งแบบรูปพรีวิว (Telegram บีบอัด/ย่อขนาดเอง) แทนไฟล์คุณภาพเต็ม ค่าเริ่มต้นปิด = คุณภาพเต็ม */
    var Context.sendAsPhoto: Boolean
        get() = sp(this).getBoolean(KEY_SEND_AS_PHOTO, false)
        set(v) { sp(this).edit().putBoolean(KEY_SEND_AS_PHOTO, v).apply() }

    /** จำนวนรูปที่ส่งเข้ากลุ่มไปแล้ว (สะสม) */
    var Context.sentCount: Int
        get() = sp(this).getInt(KEY_COUNT, 0)
        set(v) { sp(this).edit().putInt(KEY_COUNT, v).apply() }

    fun addSent(ctx: Context, n: Int) {
        ctx.sentCount = ctx.sentCount + n
    }

    /** เวลา (epoch ms) ที่ส่งสำเร็จครั้งล่าสุด — 0 = ยังไม่เคยส่งสำเร็จเลย ใช้โชว์สถานะในแอป */
    var Context.lastSendSuccessAt: Long
        get() = sp(this).getLong(KEY_LAST_SUCCESS, 0L)
        set(v) { sp(this).edit().putLong(KEY_LAST_SUCCESS, v).apply() }

    /** Bot Token/Chat ID ถูกฝังไว้ตอน build (BuildConfig) ไม่ได้มาจากผู้ใช้อีกต่อไป */
    fun hasConfig(): Boolean =
        BuildConfig.BOT_TOKEN.isNotEmpty() && BuildConfig.CHAT_ID.isNotEmpty()

    // ---- รายการรูปที่ "รู้จักแล้ว" (ส่งแล้ว หรือถูกตั้งเป็น baseline) เพื่อกันส่งซ้ำ ----

    fun knownIds(ctx: Context): MutableSet<String> =
        HashSet(sp(ctx).getStringSet(KEY_KNOWN, emptySet()) ?: emptySet())

    fun isKnown(ctx: Context, key: String): Boolean =
        sp(ctx).getStringSet(KEY_KNOWN, emptySet())?.contains(key) == true

    fun addKnown(ctx: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val set = knownIds(ctx)
        set.addAll(keys)
        sp(ctx).edit().putStringSet(KEY_KNOWN, set).apply()
    }

    fun clearKnown(ctx: Context) {
        sp(ctx).edit().remove(KEY_KNOWN).apply()
    }
}
