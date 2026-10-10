package com.application.sensorforge.sdk.camera

import android.graphics.Bitmap
import com.application.sensorforge.sdk.perception.Detection

/** M1 learned detector: upright bitmap in, normalised boxes out; backend and latency are logged. */
interface Detector : AutoCloseable {
    val backend: String
    val lastMs: Double
    fun detect(upright: Bitmap): List<Detection>
}
