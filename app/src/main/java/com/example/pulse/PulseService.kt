package com.example.pulse

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import android.net.TrafficStats

class PulseService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val client = OkHttpClient()

    // Starts the service, creates a notification channel, and begins collecting stats
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (grantedPermissions()) {
            val channelId = createNotificationChannel("pulse_monitor", "Pulse Monitor")
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Pulse")
                .setContentText("Monitoring system stats")
                .setSmallIcon(R.drawable.ic_notification)
                .build()

            startForeground(1, notification)

            serviceScope.launch {
                while (isActive) {
                    try {
                        collectAndSendStats()
                    } catch (e: Exception) {
                        Log.e("PulseService", "Error collecting or sending stats", e)
                    }
                    delay(60 * 1000) // Wait for 1 minute before next update
                }
            }
        } else {
            Log.w("PulseService", "Required permissions not granted. Service not started.")
            stopSelf()
        }

        return START_STICKY
    }

    // Creates a notification channel
    private fun createNotificationChannel(channelId: String, channelName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(channel)
        }
        return channelId
    }

    //checks if necessary permissions are granted
    private fun grantedPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && backgroundLocation
    }

    // Retrieves the current battery level, network info, etc
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getWifiNetwork(): String {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
            return "Ethernet"
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val wifiInfo = wifiManager.connectionInfo
                return if (wifiInfo != null && wifiInfo.ssid != null && wifiInfo.ssid != "<unknown ssid>") {
                    wifiInfo.ssid.removeSurrounding("\"")
                } else {
                    "Unknown"
                }
            } else {
                return "Unknown (Location permission not granted)"
            }
        } else {
            return "WiFi Disabled"
        }
    }

    private fun getWifiSignalStrength(): Int {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val wifiInfo = wifiManager.connectionInfo
                return wifiInfo.rssi
            } else {
                return -1 // Location permission probably not granted
            }
        } else {
            return -1
        }
    }

    private fun mobileDataAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
    }

    private fun getRamUsage(): Float {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem - memoryInfo.availMem).toFloat() / memoryInfo.totalMem
    }

    private fun getStorageUsage(): Float {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalSize = totalBlocks * blockSize
        val availableSize = availableBlocks * blockSize
        return (totalSize - availableSize).toFloat() / totalSize
    }

    // Gets network traffic (download and upload speeds) over a 60-second period
    private suspend fun getNetworkTraffic(): JSONObject = withContext(Dispatchers.Default) {
        val rxBytesStart = TrafficStats.getTotalRxBytes()
        val txBytesStart = TrafficStats.getTotalTxBytes()
        val startTime = System.currentTimeMillis()

        repeat(60) {
            delay(1000)
        }

        val rxBytesEnd = TrafficStats.getTotalRxBytes()
        val txBytesEnd = TrafficStats.getTotalTxBytes()
        val endTime = System.currentTimeMillis()

        val rxBytes = rxBytesEnd - rxBytesStart
        val txBytes = txBytesEnd - txBytesStart
        val duration = (endTime - startTime) / 1000.0

        val downloadSpeed = (rxBytes / duration) / (1024 * 1024) * 8
        val uploadSpeed = (txBytes / duration) / (1024 * 1024) * 8

        JSONObject().apply {
            put("download_speed_mbps", downloadSpeed)
            put("upload_speed_mbps", uploadSpeed)
        }
    }

    private suspend fun collectAndSendStats() {
        val prefs = getSharedPreferences("PulsePrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

        val networkTraffic = getNetworkTraffic()

        val stats = JSONObject().apply {
            put("device_id", deviceId)
            put("battery_level", getBatteryLevel())
            put("wifi_network", getWifiNetwork())
            put("wifi_signal_strength", getWifiSignalStrength())
            put("mobile_data_available", mobileDataAvailable())
            put("ram_usage", getRamUsage())
            put("storage_usage", getStorageUsage())
            put("network_traffic", networkTraffic)
        }

        sendStatsToServer(stats)
    }

    private fun sendStatsToServer(stats: JSONObject) {
        val request = Request.Builder()
            .url("http://192.168.10.1:3000/api/stats")
            .post(stats.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PulseService", "Failed to send stats", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("PulseService", "Stats sent successfully")
                } else {
                    Log.e("PulseService", "Server returned error ${response.code}")
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Cancels the coroutine scope when the service is destroyed
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
