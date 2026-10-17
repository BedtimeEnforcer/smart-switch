package com.ble.rawreader

/**
 * 字节数组转十六进制字符串的几种格式。
 * 对应 nRF Connect 设备详情里 "RAW" 标签页的内容。
 */

/** 单行、大写两位、空格分隔：02 01 06 0A ...（复制的默认格式） */
fun ByteArray.toHexSpaced(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size * 3)
    for (i in indices) {
        if (i > 0) sb.append(' ')
        sb.append(HEX[(this[i].toInt() shr 4) and 0x0F])
        sb.append(HEX[this[i].toInt() and 0x0F])
    }
    return sb.toString()
}

/** 换行分块显示：每 bytesPerLine 字节换一行，界面上更易读 */
fun ByteArray.toHexBlocks(bytesPerLine: Int = 16): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size * 3 + size / bytesPerLine + 1)
    for (i in indices) {
        if (i > 0) {
            sb.append(if (i % bytesPerLine == 0) '\n' else ' ')
        }
        sb.append(HEX[(this[i].toInt() shr 4) and 0x0F])
        sb.append(HEX[this[i].toInt() and 0x0F])
    }
    return sb.toString()
}

/** 连续无分隔的大写十六进制：0201060A...（粘到别的系统里常用） */
fun ByteArray.toHexPlain(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX[(b.toInt() shr 4) and 0x0F])
        sb.append(HEX[b.toInt() and 0x0F])
    }
    return sb.toString()
}

/** 代码字面量形式：0x02, 0x01, 0x06 */
fun ByteArray.toHexArrayLiteral(): String {
    if (isEmpty()) return ""
    return joinToString(", ") { "0x%02X".format(it) }
}

/** ASCII 预览：不可打印字符一律显示 '.'，与 nRF Connect / Wireshark 一致 */
fun ByteArray.toAsciiPreview(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size)
    for (b in this) {
        val c = b.toInt() and 0xFF
        sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()
