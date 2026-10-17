package com.ble.rawreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/**
 * 高级设置页。入口是连续点主界面标题 10 次，
 * 这里可以出现技术名词：MAC 地址、RAW、复制格式等。
 *
 * 能做的事：
 *   - 查看 / 修改当前锁定的 MAC 地址（并恢复默认值）
 *   - 切换「复制按钮」拷贝的十六进制格式
 *   - 查看 RAW 变化记录、清空记录
 */
class SecretActivity : Activity() {

    companion object {
        /** MAC 校验放宽：允许 : 或 - 分隔，也允许 12 位连续十六进制 */
        private val MAC_PATTERN = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

        private const val FORMAT_SPACED = 0   // 02 01 06 ...
        private const val FORMAT_PLAIN = 1    // 020106...
        private const val FORMAT_ARRAY = 2    // 0x02, 0x01, 0x06
    }

    private lateinit var tvSecretBack: TextView
    private lateinit var tvSecretMac: TextView
    private lateinit var etMac: EditText
    private lateinit var btnSaveMac: Button
    private lateinit var btnResetMac: Button
    private lateinit var rgFormat: RadioGroup
    private lateinit var rbFormatSpaced: RadioButton
    private lateinit var rbFormatPlain: RadioButton
    private lateinit var rbFormatArray: RadioButton
    private lateinit var btnViewRecords: Button
    private lateinit var btnClearRecords: Button

    /** 刷新 RadioGroup 选中项时会触发监听，用这个标志避免把刷新当成用户操作 */
    private var suppressFormatChange: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret)
        bindViews()
        setupEvents()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从记录页返回、或清空记录后回来，条数要更新
        refresh()
    }

    private fun bindViews() {
        tvSecretBack = findViewById(R.id.tvSecretBack)
        tvSecretMac = findViewById(R.id.tvSecretMac)
        etMac = findViewById(R.id.etSecretMac)
        btnSaveMac = findViewById(R.id.btnSecretSaveMac)
        btnResetMac = findViewById(R.id.btnSecretResetMac)
        rgFormat = findViewById(R.id.rgSecretFormat)
        rbFormatSpaced = findViewById(R.id.rbFormatSpaced)
        rbFormatPlain = findViewById(R.id.rbFormatPlain)
        rbFormatArray = findViewById(R.id.rbFormatArray)
        btnViewRecords = findViewById(R.id.btnSecretViewRecords)
        btnClearRecords = findViewById(R.id.btnSecretClearRecords)

        etMac.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        etMac.hint = getString(R.string.secret_mac_hint)
    }

    private fun setupEvents() {
        tvSecretBack.setOnClickListener { finish() }

        btnSaveMac.setOnClickListener { saveMac() }

        btnResetMac.setOnClickListener {
            Prefs.setTarget(this, Prefs.DEFAULT_MAC, null)
            Toast.makeText(this, "已恢复默认 MAC：${Prefs.DEFAULT_MAC}", Toast.LENGTH_SHORT).show()
            refresh()
        }

        rgFormat.setOnCheckedChangeListener { _, checkedId ->
            if (suppressFormatChange) return@setOnCheckedChangeListener
            val format = when (checkedId) {
                R.id.rbFormatPlain -> FORMAT_PLAIN
                R.id.rbFormatArray -> FORMAT_ARRAY
                else -> FORMAT_SPACED
            }
            Prefs.setCopyFormat(this, format)
            Toast.makeText(this, "复制格式已切换", Toast.LENGTH_SHORT).show()
        }

        btnViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }

        btnClearRecords.setOnClickListener { confirmClearRecords() }
    }

    /** 刷新当前 MAC、复制格式、记录条数 */
    private fun refresh() {
        val mac = Prefs.getTargetMac(this)
        tvSecretMac.text = mac
        if (etMac.text.isEmpty()) etMac.setText(mac)

        suppressFormatChange = true
        val id = when (Prefs.getCopyFormat(this)) {
            FORMAT_PLAIN -> R.id.rbFormatPlain
            FORMAT_ARRAY -> R.id.rbFormatArray
            else -> R.id.rbFormatSpaced
        }
        rgFormat.check(id)
        suppressFormatChange = false

        val n = RecordStore.count(this)
        btnViewRecords.text = getString(R.string.secret_view_records) + "（$n 条）"
        btnClearRecords.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun saveMac() {
        val input = etMac.text.toString().trim()
        val normalized = normalizeMac(input)
        if (normalized == null) {
            Toast.makeText(this, "格式不对，应为 12 位十六进制，如 AA:BB:CC:DD:EE:FF", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setTarget(this, normalized, null)
        Toast.makeText(this, "已锁定 MAC：$normalized", Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** 把用户输入统一成大写冒号格式；不合法返回 null */
    private fun normalizeMac(input: String): String? {
        val s = input.trim()
        if (!MAC_PATTERN.matches(s)) return null
        val hex = s.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        return hex.uppercase().chunked(2).joinToString(":")
    }

    private fun confirmClearRecords() {
        val n = RecordStore.count(this)
        if (n == 0) {
            Toast.makeText(this, "还没有记录", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空 RAW 记录")
            .setMessage("确定删除全部 $n 条 RAW 记录吗？删除后不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                RecordStore.clear(this)
                refresh()
                Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
