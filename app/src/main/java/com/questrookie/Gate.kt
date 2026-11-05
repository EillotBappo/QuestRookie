package com.questrookie

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** Why a download can't start yet, or null if it can. Pure, so the rules can be checked without a headset. */
fun downloadBlock(needsNetwork: Boolean, wifiOnly: Boolean, onWifi: Boolean, chargingOnly: Boolean, charging: Boolean): String? = when {
    !needsNetwork -> null
    wifiOnly && !onWifi -> "Waiting for Wi-Fi"
    chargingOnly && !charging -> "Waiting for charger"
    else -> null
}

fun onWifi(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

fun isCharging(ctx: Context): Boolean {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}
