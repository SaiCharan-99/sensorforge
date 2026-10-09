package com.application.sensorforge.sdk.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.application.sensorforge.sdk.detect.LocationFix

/**
 * Last location fix with accuracy and age for incident evidence (tech spec §7 `last_fix_with_accuracy`).
 * Only reads providers that work offline (GPS) or cached fixes; never makes a network call itself.
 * Updates run only between [start] and [stop] (armed + visible). Without permission, [lastFix] is null.
 */
class LocationTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var best: Location? = null
    private val listener = LocationListener { l -> if (better(l, best)) best = l }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        stop()
        cached().forEach { if (better(it, best)) best = it }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            if (p in lm.allProviders) runCatching { lm.requestLocationUpdates(p, 10_000L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() { runCatching { lm.removeUpdates(listener) } }

    fun lastFix(): LocationFix? {
        if (!hasPermission()) return null
        val l = (cached() + listOfNotNull(best)).fold(null as Location?) { acc, x -> if (better(x, acc)) x else acc } ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return LocationFix(l.latitude, l.longitude, l.accuracy, ageMs)
    }

    @SuppressLint("MissingPermission")
    private fun cached(): List<Location> = if (!hasPermission()) emptyList() else
        lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }

    /** Newer wins unless more than 2 min newer is not true and the other is clearly more accurate. */
    private fun better(a: Location, b: Location?): Boolean {
        if (b == null) return true
        val dt = a.elapsedRealtimeNanos - b.elapsedRealtimeNanos
        if (dt > 120_000_000_000L) return true
        if (dt < -120_000_000_000L) return false
        return a.accuracy <= b.accuracy || dt > 0 && a.accuracy <= b.accuracy * 1.5f
    }
}
