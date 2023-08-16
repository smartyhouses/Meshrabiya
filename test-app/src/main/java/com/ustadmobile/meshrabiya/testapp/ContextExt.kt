package com.ustadmobile.meshrabiya.testapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

fun Context.getActivityContext(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> this.baseContext.getActivityContext()
    else -> throw IllegalArgumentException("Not an activity context")
}

/**
 * On Android 13+ we can use the NEARBY_WIFI_DEVICES permission instead of the location permission.
 * On earlier versions, we need fine location permission
 */
val NEARBY_WIFI_PERMISSION_NAME = if(Build.VERSION.SDK_INT >= 33){
    Manifest.permission.NEARBY_WIFI_DEVICES
}else {
    Manifest.permission.ACCESS_FINE_LOCATION
}


fun Context.hasNearbyWifiDevicesOrLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this, NEARBY_WIFI_PERMISSION_NAME
    ) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasBluetoothConnectPermission(): Boolean {
    return if(Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }else {
        true
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshr_settings")

fun Context.meshrabiyaDeviceInfoStr(): String {
    val wifiManager = getSystemService(WifiManager::class.java)
    val hasStaConcurrency = Build.VERSION.SDK_INT >= 31 &&
            wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported

    return buildString {
        append("Meshrabiya: Version :${MeshrabiyaConstants.VERSION}\n")
        append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("Device: ${Build.MANUFACTURER} - ${Build.MODEL}\n")
        append("5Ghz supported: ${wifiManager.is5GHzBandSupported}\n")
        append("Local-only station concurrency: $hasStaConcurrency\n")
    }
}