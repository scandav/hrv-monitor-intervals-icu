package com.example.hrvmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Talks to any chest strap that implements the standard Bluetooth "Heart Rate" profile
 * (service 0x180D). The Suunto Smart Sensor does, and it includes beat-to-beat RR intervals.
 * All listener callbacks are delivered on the main thread.
 */
@SuppressLint("MissingPermission") // permissions are checked in MainActivity before use
class HeartRateSensor(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onDeviceFound(address: String, name: String)
        fun onConnectionChanged(connected: Boolean, message: String)
        fun onHeartRate(bpm: Int, rrMs: List<Double>)
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 15_000L

        /** Parses a Heart Rate Measurement (0x2A37) packet into bpm + RR intervals in ms. */
        fun parse(data: ByteArray): Pair<Int, List<Double>> {
            if (data.isEmpty()) return 0 to emptyList()
            val flags = data[0].toInt() and 0xFF
            var i = 1
            val bpm: Int
            if (flags and 0x01 != 0) {
                bpm = u16(data, i); i += 2
            } else {
                bpm = data[i].toInt() and 0xFF; i += 1
            }
            if (flags and 0x08 != 0) i += 2 // energy expended field, skip
            val rr = ArrayList<Double>()
            if (flags and 0x10 != 0) {
                while (i + 1 < data.size) {
                    rr.add(u16(data, i) * 1000.0 / 1024.0) // units of 1/1024 s
                    i += 2
                }
            }
            return bpm to rr
        }

        private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var userDisconnected = false
    private var scanning = false
    private val seen = HashSet<String>()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------- scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: ""
            val isHr = result.scanRecord?.serviceUuids?.contains(ParcelUuid(HR_SERVICE)) == true
            val looksLikeSuunto = name.contains("suunto", true) || name.contains("smart sensor", true)
            if (!isHr && !looksLikeSuunto) return
            if (!seen.add(dev.address)) return
            main.post { listener.onDeviceFound(dev.address, name.ifEmpty { dev.address }) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post { listener.onConnectionChanged(false, "Bluetooth scan failed (code $errorCode)") }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        seen.clear()
        // No hardware filter: some straps only put the HR service in the scan response.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        main.postDelayed({ stopScan() }, SCAN_MS)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // ---------- connection ----------

    fun connect(address: String) {
        stopScan()
        closeGatt()
        val device = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null } ?: return
        targetAddress = address
        userDisconnected = false
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnected = true
        stopScan()
        closeGatt()
    }

    private fun closeGatt() {
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { listener.onConnectionChanged(false, "Connected, setting up…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt == g) gatt = null
                val addr = targetAddress
                if (!userDisconnected && addr != null) {
                    // Strap dropped out: keep trying in the background (autoConnect = true).
                    main.post { listener.onConnectionChanged(false, "Sensor lost – reconnecting…") }
                    val device = adapter?.getRemoteDevice(addr)
                    main.post { if (!userDisconnected && gatt == null) gatt = device?.connectGatt(context, true, this, BluetoothDevice.TRANSPORT_LE) }
                } else {
                    main.post { listener.onConnectionChanged(false, "Disconnected") }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            val desc = ch?.getDescriptor(CCCD)
            if (ch == null || desc == null) {
                main.post { listener.onConnectionChanged(false, "This device has no heart-rate service") }
                return
            }
            g.setCharacteristicNotification(ch, true)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, enable)
            } else {
                @Suppress("DEPRECATION")
                desc.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            main.post { listener.onConnectionChanged(ok, if (ok) "Connected ✓" else "Could not enable heart rate (status $status)") }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(c, value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(c, c.value ?: return)
        }

        private fun handle(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != HR_MEASUREMENT) return
            val (bpm, rr) = parse(value)
            main.post { listener.onHeartRate(bpm, rr) }
        }
    }
}
