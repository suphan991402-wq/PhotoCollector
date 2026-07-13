package com.photocollector.app

import android.content.Context

/** ตัวเก็บค่าตั้งค่า (SharedPreferences) */
object Prefs {
    private const val FILE = "photocollector_prefs"
    private const val KEY_ENABLED = "auto_enabled"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_CHAT = "chat_id"
    private const val KEY_COUNT = "sent_count"
    private const val KEY_KNOWN = "known_ids"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** เปิด/ปิดโหมดส่งอัตโนมัติเบื้องหลัง */
    var Context.autoEnabled: Boolean
        get() = sp(this).getBoolean(KEY_ENABLED, false)
        set(v) { sp(this).edit().putBoolean(KEY_ENABLED, v).apply() }

    /** Bot token จาก @BotFather */
    var Context.botToken: String
        get() = sp(this).getString(KEY_TOKEN, "") ?: ""
        set(v) { sp(this).edit().putString(KEY_TOKEN, v.trim()).apply() }

    /** chat id ของกลุ่มปลายทาง (กลุ่มมักขึ้นต้นด้วย -100...) */
    var Context.chatId: String
        get() = sp(this).getString(KEY_CHAT, "") ?: ""
        set(v) { sp(this).edit().putString(KEY_CHAT, v.trim()).apply() }

    /** จำนวนรูปที่ส่งเข้ากลุ่มไปแล้ว (สะสม) */
    var Context.sentCount: Int
        get() = sp(this).getInt(KEY_COUNT, 0)
        set(v) { sp(this).edit().putInt(KEY_COUNT, v).apply() }

    fun addSent(ctx: Context, n: Int) {
        ctx.sentCount = ctx.sentCount + n
    }

    fun hasConfig(ctx: Context): Boolean =
        ctx.botToken.isNotEmpty() && ctx.chatId.isNotEmpty()

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
