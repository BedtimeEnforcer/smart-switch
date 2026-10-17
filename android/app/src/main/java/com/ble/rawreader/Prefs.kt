package com.ble.rawreader

import android.content.Context

/**
 * 目标设备的持久化配置。
 * 存三样：目标 MAC 地址、设备名（仅用于显示）、复制时用的十六进制格式。
 *
 * 目标 MAC 有一个内置默认值 [DEFAULT_MAC]，用户没改过时一直用它，
 * 这样打开 App 就立刻开始扫描，不需要任何选择步骤。
 */
object Prefs {

    /** 内置默认目标 MAC 地址（占位值，需用户在高级设置里改成自己的设备） */
    const val DEFAULT_MAC = "00:00:00:00:00:00"

    private const val FILE = "ble_raw_reader"
    private const val KEY_MAC = "target_mac"
    private const val KEY_NAME = "target_name"
    private const val KEY_FORMAT = "copy_format"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 读不到配置时返回内置默认 MAC，永远不为 null */
    fun getTargetMac(ctx: Context): String =
        sp(ctx).getString(KEY_MAC, null)?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_MAC

    fun getTargetName(ctx: Context): String? = sp(ctx).getString(KEY_NAME, null)

    fun setTarget(ctx: Context, mac: String, name: String?) {
        sp(ctx).edit()
            .putString(KEY_MAC, mac.trim().uppercase())
            .putString(KEY_NAME, name?.takeIf { it.isNotBlank() } ?: "未命名设备")
            .apply()
    }

    fun clearTarget(ctx: Context) {
        sp(ctx).edit().remove(KEY_MAC).remove(KEY_NAME).apply()
    }

    /** 0 = 空格分隔，1 = 连续无空格，2 = 0x 数组；默认用连续无空格 */
    fun getCopyFormat(ctx: Context): Int = sp(ctx).getInt(KEY_FORMAT, 1)

    fun setCopyFormat(ctx: Context, format: Int) {
        sp(ctx).edit().putInt(KEY_FORMAT, format).apply()
    }
}
