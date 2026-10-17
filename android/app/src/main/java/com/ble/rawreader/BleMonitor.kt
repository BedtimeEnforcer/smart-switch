package com.ble.rawreader

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * BLE 扫描封装：只盯一个 MAC 地址，抓到它的广播包就把整包回传。
 *
 * 不连接、不读 GATT —— 要的就是广播包里的原始数据。
 * 界面只显示目标 MAC 的数据，所以这里不做「附近设备列表」的收集。
 */
class BleMonitor(private val context: Context, private val callback: Callback) {

    interface Callback {
        /** 抓到目标设备的一次广播 */
        fun onTargetFound(result: ScanResult)
        /** 需要提示用户的信息（权限不足、蓝牙没开、扫描失败等） */
        fun onMessage(msg: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var targetMac: String = ""
    private var running = false
    private var scanner: BluetoothLeScanner? = null

    @SuppressLint("MissingPermission") // 权限已在 MainActivity 申请通过后才会进来
    fun start(targetMac: String) {
        stop()

        if (!hasScanPermission()) {
            callback.onMessage("缺少蓝牙扫描权限")
            return
        }

        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) {
            callback.onMessage("该设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            callback.onMessage("请先开启手机蓝牙")
            return
        }

        this.targetMac = targetMac.trim().uppercase()

        val sc = adapter.bluetoothLeScanner
        if (sc == null) {
            callback.onMessage("蓝牙扫描器不可用（请确认蓝牙已开启）")
            return
        }
        scanner = sc

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            // 不过滤，全量扫描后在代码里按 MAC 比对：
            // 部分机型的过滤规则行为不一致，手工比对最稳
            sc.startScan(null, settings, scanCallback)
            running = true
        } catch (e: SecurityException) {
            callback.onMessage("没有扫描权限：${e.message}")
        } catch (e: Exception) {
            callback.onMessage("启动扫描失败：${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (running) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
            running = false
        }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            running = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "系统注册扫描失败"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "该机型不支持此扫描方式"
                SCAN_FAILED_INTERNAL_ERROR -> "蓝牙芯片内部错误"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "蓝牙硬件资源不足"
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描过于频繁，请稍后再试"
                else -> "未知错误"
            }
            mainHandler.post { callback.onMessage("扫描失败（$errorCode）：$reason") }
        }
    }

    private fun handle(result: ScanResult) {
        val mac = result.device?.address ?: return
        if (mac.equals(targetMac, ignoreCase = true)) {
            mainHandler.post { callback.onTargetFound(result) }
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 运行时需要申请的权限清单（Android 12 前后不一样） */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
