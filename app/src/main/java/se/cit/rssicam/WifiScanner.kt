package se.cit.rssicam

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the most recent Wi‑Fi scan results and periodically asks the system
 * for a fresh scan.  Note: Android throttles foreground apps to 4 scans per
 * 2 minutes unless "Wi‑Fi scan throttling" is turned off in Developer options.
 * getScanResults() always returns the system's latest cached results, so each
 * capture records the freshest data available plus its age.
 */
class WifiScanner(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    @Volatile var lastScanCompletedAt: Long = 0L   // SystemClock.elapsedRealtime()
    @Volatile var lastScanSucceeded: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            lastScanSucceeded = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            lastScanCompletedAt = SystemClock.elapsedRealtime()
        }
    }

    private val periodic = object : Runnable {
        override fun run() {
            requestScan()
            handler.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    fun start() {
        if (!registered) {
            context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            registered = true
        }
        handler.removeCallbacks(periodic)
        handler.post(periodic)
    }

    fun stop() {
        handler.removeCallbacks(periodic)
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = runCatching { wifi.startScan() }.getOrDefault(false)

    /** Snapshot of the latest scan results as JSON. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun snapshot(): JSONObject {
        val out = JSONObject()
        val arr = JSONArray()
        val nowBoot = SystemClock.elapsedRealtime()
        val results = runCatching { wifi.scanResults }.getOrNull() ?: emptyList()
        for (r in results.sortedByDescending { it.level }) {
            val o = JSONObject()
            val ssid = if (Build.VERSION.SDK_INT >= 33) {
                r.wifiSsid?.toString()?.trim('"') ?: r.SSID
            } else r.SSID
            o.put("ssid", ssid ?: "")
            o.put("bssid", r.BSSID ?: "")
            o.put("rssi", r.level)                 // dBm
            o.put("freq", r.frequency)             // MHz
            o.put("width", r.channelWidth)
            if (Build.VERSION.SDK_INT >= 30) o.put("std", r.wifiStandard)
            // age of this particular result in ms (timestamp is in µs since boot)
            o.put("age_ms", nowBoot - r.timestamp / 1000)
            arr.put(o)
        }
        out.put("aps", arr)
        out.put("count", arr.length())
        out.put("last_scan_age_ms", if (lastScanCompletedAt == 0L) -1 else nowBoot - lastScanCompletedAt)
        out.put("last_scan_ok", lastScanSucceeded)

        // Currently connected AP (if any)
        runCatching {
            val info = wifi.connectionInfo
            if (info != null && info.networkId != -1) {
                val c = JSONObject()
                c.put("ssid", info.ssid?.trim('"') ?: "")
                c.put("bssid", info.bssid ?: "")
                c.put("rssi", info.rssi)
                c.put("freq", info.frequency)
                c.put("link_mbps", info.linkSpeed)
                out.put("connected", c)
            }
        }
        return out
    }

    companion object {
        const val SCAN_PERIOD_MS = 20_000L
    }
}
