package com.application.sensorforge.sdk.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.application.sensorforge.sdk.contracts.ProcedureSpec
import com.application.sensorforge.sdk.contracts.Timeline
import com.application.sensorforge.sdk.perception.Detection
import com.application.sensorforge.sdk.perception.FrameQuality
import com.application.sensorforge.sdk.perception.Learned
import com.application.sensorforge.sdk.perception.Pt
import com.application.sensorforge.sdk.perception.RawCode
import com.application.sensorforge.sdk.perception.Tags
import com.application.sensorforge.sdk.perception.TimelineBuilder
import com.application.sensorforge.sdk.train.Head
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * Turns a video already on the phone into a timeline through the same perception path as the live
 * camera (QR tags, quality gate, learned detector, M2 crops + head, tracker, timeline builder).
 * Frames are sampled at [fps]; nothing leaves the phone. The result is a real (not simulated) example.
 */
class ClipImporter(
    private val context: Context,
    private val spec: ProcedureSpec,
    private val head: Head? = null,
    private val lockedModels: Set<String> = emptySet(),
) {
    class Result(val timeline: Timeline, val frames: Int, val usableFrames: Int, val startUtcMs: Long, val detector: String)

    private val reader = QRCodeMultiReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun run(uri: Uri, fps: Double = 5.0, onProgress: (Float) -> Unit = {}): Result {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read the clip duration")
            require(durMs > 500) { "Clip is shorter than half a second" }
            val embedder = Embedders.forLock(context, lockedModels)
            val usableHead = head?.takeIf { it.inputDim == embedder.dim }
            val chain = DetectorChain.open(context)
            val specClasses = (spec.entities.map { it.cls } + spec.prohibited.filterIsInstance<com.application.sensorforge.sdk.contracts.ForbiddenClass>().map { it.cls }).toSet()
            val b = TimelineBuilder("clip-import-${System.currentTimeMillis()}", Tags.STACK, targetsOf = { cls -> spec.entities.filter { it.cls == cls }.map { it.id }.ifEmpty { listOf(cls) } }, detectorZones = spec.steps.mapNotNull { it.zone }.filter { it.endsWith("_interior") }.toSet())
            val total = (durMs / 1000.0 * fps).toInt().coerceAtLeast(1)
            var usable = 0
            val quality = TagFrameReader()
            var learned: List<Detection> = emptyList()
            for (i in 0 until total) {
                val tUs = (i / fps * 1_000_000).toLong()
                val bmp = (if (android.os.Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280) else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
                val luma = ByteArray(w * h) { k -> val p = px[k]; (((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte() }
                val q: FrameQuality = quality.qualityOf(luma, w, h)
                val codes = try {
                    reader.decodeMultiple(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), hints).mapNotNull { r ->
                        val pts = r.resultPoints?.filterNotNull()?.map { Pt(it.x / w, it.y / h) }.orEmpty()
                        if (pts.isEmpty()) null else RawCode(r.text, pts)
                    }
                } catch (_: NotFoundException) { emptyList() } finally { reader.reset() }
                if (q.usable()) usable++
                if (chain != null && q.usable() && i % 1 == 0) learned = try { chain.detector.detect(bmp) } catch (e: Exception) { emptyList() }
                val tagObs = Tags.observe(tUs * 1000, i.toLong(), codes, q) { d -> classify(usableHead, embedder, d, bmp) }
                b.add(if (learned.isEmpty()) tagObs else tagObs.copy(detections = Learned.merge(tagObs.detections, learned, specClasses)))
                if (i % 5 == 0) onProgress(i / total.toFloat())
                bmp.recycle()
            }
            onProgress(1f)
            val dateMs = parseDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val startUtc = dateMs ?: (System.currentTimeMillis() - durMs)
            Log.i(TAG, "imported clip ${durMs} ms, $total frames sampled, $usable usable, detector ${chain?.modelId ?: "none"}, encoder ${embedder.modelId}")
            return Result(b.timeline(close = true).copy(startUtcMs = startUtc), total, usable, startUtc, chain?.modelId ?: "none (tags only)")
        } finally { mmr.release() }
    }

    private fun classify(head: Head?, embedder: CropEmbedder, d: Detection, bmp: Bitmap): Detection {
        if (head == null || d.cls !in head.classes) return d
        val x = embedder.embed(bmp, identityCrop(d.box)) ?: return d
        val p = head.predict(x)
        val best = p.indices.maxBy { p[it] }
        return d.copy(cls = head.classes[best], confidence = p[best].toDouble())
    }

    private fun parseDate(s: String?): Long? = s?.let { runCatching { java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it.substringBefore('.'))?.time }.getOrNull() }

    companion object { const val TAG = "SF.CLIP" }
}
