package com.ble.rawreader

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：只显示目标设备的信号和数据，加一个复制按钮；
 * MAC、RAW 这些技术信息不上界面。改 MAC 在高级设置里（连点标题 10 次进入）。
 * 数据变化时写一条记录，上限 1000 条，细节见 RecordStore。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSION = 1001

        private const val SECRET_CLICK_COUNT = 10
        private const val SECRET_CLICK_WINDOW = 3000L

        private const val FORMAT_SPACED = 0   // 02 01 06 ...
        private const val FORMAT_PLAIN = 1    // 020106...
        private const val FORMAT_ARRAY = 2    // 0x02, 0x01, 0x06
    }

    private lateinit var monitor: BleMonitor

    private lateinit var viewMain: LinearLayout
    private lateinit var viewBlocked: LinearLayout

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDevName: TextView
    private lateinit var tvRssi: TextView
    private lateinit var tvRaw: TextView
    private lateinit var btnCopy: Button
    private lateinit var tvBlockedMsg: TextView
    private lateinit var btnBlockedAction: Button

    private var lastRaw: ByteArray? = null
    private var storeReady: Boolean = false
    private var titleClickCount: Int = 0
    private var lastTitleClickTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        monitor = BleMonitor(this, monitorCallback)
        setupEvents()
        ensureStore()
    }

    override fun onResume() {
        super.onResume()
        // 从「开启蓝牙」等系统界面、或从高级设置页返回时，这里会自动接上
        checkAndStart()
    }

    override fun onPause() {
        super.onPause()
        monitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitor.stop()
    }

    // ---------------------------------------------------------------- 界面绑定

    private fun bindViews() {
        viewMain = findViewById(R.id.viewMain)
        viewBlocked = findViewById(R.id.viewBlocked)

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)

        tvDevName = findViewById(R.id.tvDevName)
        tvRssi = findViewById(R.id.tvRssi)
        tvRaw = findViewById(R.id.tvRaw)
        btnCopy = findViewById(R.id.btnCopy)

        tvBlockedMsg = findViewById(R.id.tvBlockedMsg)
        btnBlockedAction = findViewById(R.id.btnBlockedAction)

        tvTitle.text = getString(R.string.app_name)
        btnCopy.text = getString(R.string.copy_switch_code)
    }

    private fun setupEvents() {
        // 复制：点一下就按配置里的格式复制（默认连续无空格），不再区分长按
        btnCopy.setOnClickListener { doCopy() }

        // 高级设置入口：连续点标题 10 次进高级设置（不弹提示，保持界面简洁）
        tvTitle.setOnClickListener { onTitleClicked() }
    }

    private fun onTitleClicked() {
        val now = System.currentTimeMillis()
        titleClickCount = if (now - lastTitleClickTime > SECRET_CLICK_WINDOW) 1 else titleClickCount + 1
        lastTitleClickTime = now
        if (titleClickCount >= SECRET_CLICK_COUNT) {
            titleClickCount = 0
            lastTitleClickTime = 0L
            startActivity(Intent(this, SecretActivity::class.java))
        }
    }

    // ---------------------------------------------------------------- 流程控制

    private fun checkAndStart() {
        if (!monitor.hasScanPermission()) {
            requestPermissions(monitor.requiredPermissions(), REQ_PERMISSION)
            return
        }
        if (!isBluetoothOn()) {
            showBlocked(
                getString(R.string.bt_off_msg),
                getString(R.string.bt_off_action)
            ) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return
        }
        enterMonitorMode(Prefs.getTargetMac(this))
    }

    private fun enterMonitorMode(mac: String) {
        viewBlocked.visibility = View.GONE
        viewMain.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.scanning)
        // 界面简化：设备名统一显示为固定文案，不显示 MAC
        tvDevName.text = getString(R.string.unnamed_switch)
        tvRaw.text = getString(R.string.waiting_data)
        tvRssi.text = "—"
        lastRaw = null
        refreshCopyButtonLabel()
        monitor.start(mac)
    }

    private fun showBlocked(msg: String, btnText: String, action: () -> Unit) {
        viewMain.visibility = View.GONE
        viewBlocked.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_off)
        tvBlockedMsg.text = msg
        btnBlockedAction.text = btnText
        btnBlockedAction.setOnClickListener { action() }
    }

    // ---------------------------------------------------------------- 扫描回调

    private fun ensureStore() {
        if (storeReady) return
        RecordStore.init(this)
        storeReady = true
    }

    private val monitorCallback = object : BleMonitor.Callback {

        override fun onTargetFound(result: ScanResult) {
            val raw = result.scanRecord?.bytes
            lastRaw = raw

            // 只更新信号强度和主数据块，其他技术信息一律不上界面
            tvRssi.text = "${result.rssi} dBm"

            if (raw == null || raw.isEmpty()) {
                tvRaw.text = getString(R.string.no_data)
                return
            }

            tvRaw.text = raw.toHexBlocks()

            // 记录：存空格分隔格式；只有跟上一条不同才落库（内部有内存缓存，相同直接返回）
            ensureStore()
            RecordStore.appendIfChanged(this@MainActivity, raw.toHexSpaced())
        }

        override fun onMessage(msg: String) {
            // 扫描器给的是技术性提示，主界面不直接暴露，统一显示中性状态
            tvStatus.text = getString(R.string.scanning)
        }
    }

    private fun refreshCopyButtonLabel() {
        btnCopy.text = getString(R.string.copy_switch_code)
    }

    // ---------------------------------------------------------------- 复制

    private fun doCopy() {
        val raw = lastRaw
        if (raw == null || raw.isEmpty()) {
            Toast.makeText(this, getString(R.string.not_detected), Toast.LENGTH_SHORT).show()
            return
        }
        // 统一按配置里的格式复制（默认连续无空格）
        val text = when (Prefs.getCopyFormat(this)) {
            FORMAT_SPACED -> raw.toHexSpaced()
            FORMAT_ARRAY -> raw.toHexArrayLiteral()
            else -> raw.toHexPlain()
        }
        copyToClipboard(text)
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SwitchCode", text))
    }

    // ---------------------------------------------------------------- 权限

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            checkAndStart()
        } else {
            val neverAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    permissions.any { shouldShowRequestPermissionRationale(it).not() }
            if (neverAsk) {
                showBlocked(
                    getString(R.string.perm_denied_forever),
                    getString(R.string.settings_action)
                ) {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.fromParts("package", packageName, null)
                    startActivity(i)
                }
            } else {
                showBlocked(getString(R.string.perm_denied_msg), getString(R.string.perm_retry)) {
                    requestPermissions(monitor.requiredPermissions(), REQ_PERMISSION)
                }
            }
        }
    }

    private fun isBluetoothOn(): Boolean {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }
}
