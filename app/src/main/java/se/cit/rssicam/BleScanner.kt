package se.cit.rssicam

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Continuous BLE scan.  Keeps, per device address, the latest advertisement
 * plus a short RSSI history so a capture can record both the instantaneous
 * and the averaged RSSI.
 */
class BleScanner(context: Context) {

    private data class Entry(
        var name: String?,
        var rssi: Int,
        var txPower: Int?,
        var lastSeen: Long,
        val history: ArrayDeque<Pair<Long, Int>> = ArrayDeque(),
        var manufacturerIds: String = "",
        var serviceUuids: String = ""
    )

    private val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private var scanner: BluetoothLeScanner? = null
    private val devices = HashMap<String, Entry>()
    @Volatile var scanning = false
        private set
    @Volatile var lastError: Int = 0

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
        override fun onScanFailed(errorCode: Int) { lastError = errorCode; scanning = false }
    }

    @SuppressLint("MissingPermission")
    private fun handle(r: ScanResult) {
        val now = SystemClock.elapsedRealtime()
        val addr = r.device?.address ?: return
        // Android reports 127 (or other non-negative values) when the RSSI is unknown; never let
        // those into the history or the 5 s average.
        if (r.rssi >= 0 || r.rssi < -127) return
        val rec = r.scanRecord
        val name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull()
        val tx = if (r.txPower != ScanResult.TX_POWER_NOT_PRESENT) r.txPower else rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        synchronized(devices) {
            val e = devices.getOrPut(addr) { Entry(name, r.rssi, tx, now) }
            e.rssi = r.rssi
            e.lastSeen = now
            if (name != null) e.name = name
            if (tx != null) e.txPower = tx
            e.history.addLast(now to r.rssi)
            while (e.history.isNotEmpty() && now - e.history.first().first > HISTORY_MS) e.history.removeFirst()
            rec?.manufacturerSpecificData?.let { m ->
                if (m.size() > 0) e.manufacturerIds = (0 until m.size()).joinToString(",") { "0x%04X".format(m.keyAt(it)) }
            }
            rec?.serviceUuids?.let { u -> if (u.isNotEmpty()) e.serviceUuids = u.joinToString(",") { it.toString() } }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val s = adapter?.bluetoothLeScanner ?: return
        scanner = s
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        runCatching { s.startScan(null, settings, callback); scanning = true }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    /** Devices seen within the last [maxAgeMs] ms. */
    fun snapshot(maxAgeMs: Long = 6_000L): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val arr = JSONArray()
        synchronized(devices) {
            val it = devices.entries.iterator()
            while (it.hasNext()) {           // prune stale devices
                if (now - it.next().value.lastSeen > STALE_MS) it.remove()
            }
            devices.entries
                .filter { now - it.value.lastSeen <= maxAgeMs }
                .sortedByDescending { it.value.rssi }
                .forEach { (addr, e) ->
                    val o = JSONObject()
                    o.put("addr", addr)
                    o.put("name", e.name ?: "")
                    o.put("rssi", e.rssi)
                    val h = e.history.filter { now - it.first <= HISTORY_MS }
                    if (h.isNotEmpty()) {
                        o.put("rssi_avg", h.map { it.second }.average())
                        o.put("n", h.size)
                    }
                    e.txPower?.let { o.put("tx_power", it) }
                    o.put("age_ms", now - e.lastSeen)
                    if (e.manufacturerIds.isNotEmpty()) o.put("mfg", e.manufacturerIds)
                    if (e.serviceUuids.isNotEmpty()) o.put("uuids", e.serviceUuids)
                    arr.put(o)
                }
        }
        val out = JSONObject()
        out.put("devices", arr)
        out.put("count", arr.length())
        out.put("scanning", scanning)
        if (lastError != 0) out.put("error", lastError)
        return out
    }

    companion object {
        const val HISTORY_MS = 5_000L
        const val STALE_MS = 60_000L
    }
}
