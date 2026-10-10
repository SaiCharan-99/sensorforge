package com.application.sensorforge.sdk.camera

import android.content.Context
import android.util.Log
import com.application.sensorforge.sdk.runtime.ModelRegistry

/** Learned detector in use (M1): EfficientDet-Lite2, else Lite0; null means tagged props only. */
class DetectorChain private constructor(val detector: Detector, val modelId: String) {
    companion object {
        private var cached: DetectorChain? = null
        private var failed = false

        /** Opens the best installed detector once; returns null (tags only) if none can load. */
        @Synchronized
        fun open(context: Context): DetectorChain? {
            cached?.let { return it }
            if (failed) return null
            val reg = ModelRegistry.get(context)
            val y = reg.state("m1_yoloe_11s")
            if (y.usable) try {
                return DetectorChain(OrtYoloDetector(context, reg.deviceFile("m1_yoloe_11s")), "m1_yoloe_11s").also { cached = it }
            } catch (e: Exception) { Log.w("SF.M1", "m1_yoloe_11s failed to load: ${e.message}") }
            for (id in listOf("m1_effdet_lite2", "m1_effdet_lite0")) {
                val st = reg.state(id)
                if (!st.usable) continue
                try {
                    return DetectorChain(MpObjectDetector(context, st.entry.file!!), id).also { cached = it }
                } catch (e: Exception) {
                    Log.w("SF.M1", "$id failed to load: ${e.message}")
                }
            }
            failed = true
            return null
        }
    }
}
