package com.application.sensorforge.sdk.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.application.sensorforge.sdk.perception.BBox
import com.application.sensorforge.sdk.perception.CropEmbedding
import com.application.sensorforge.sdk.perception.FrameQuality
import com.application.sensorforge.sdk.perception.Pt
import com.application.sensorforge.sdk.perception.RawCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * Reads one YUV_420_888 frame: QR codes (ZXing core, CPU), quality (mean luma, Laplacian
 * variance) and, on request, crop embeddings. Coordinates leave here upright and normalised.
 */
class TagFrameReader {
    private val reader = QRCodeMultiReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    private var luma = ByteArray(0)

    class Frame(
        val codes: List<RawCode>,
        val quality: FrameQuality,
        val rotation: Int,
        val width: Int,
        val height: Int,
        /** Upright width / height. */
        val aspect: Float,
        val decodeMs: Double,
        private val image: ImageProxy,
        private val luma: ByteArray,
    ) {
        /** Upright-normalised box → image-space pixel rect. */
        private fun toImage(b: BBox): IntArray {
            val pts = listOf(Pt(b.l, b.t), Pt(b.r, b.b)).map { unrotate(it) }
            return intArrayOf(
                pts.minOf { it.first }.coerceIn(0, width), pts.minOf { it.second }.coerceIn(0, height),
                pts.maxOf { it.first }.coerceIn(0, width), pts.maxOf { it.second }.coerceIn(0, height),
            )
        }

        private fun unrotate(p: Pt): Pair<Int, Int> = when (rotation) {
            90 -> (p.y * width).toInt() to ((1 - p.x) * height).toInt()
            180 -> ((1 - p.x) * width).toInt() to ((1 - p.y) * height).toInt()
            270 -> ((1 - p.y) * width).toInt() to (p.x * height).toInt()
            else -> (p.x * width).toInt() to (p.y * height).toInt()
        }

        /** Embedding of the crop around an upright box; valid only during analysis (image open). */
        fun embed(box: BBox): FloatArray? {
            val (x0, y0, x1, y1) = toImage(box).toList()
            val u = image.planes[1]; val v = image.planes[2]
            val ub = u.buffer; val vb = v.buffer
            return CropEmbedding.compute(x0, y0, x1, y1) { x, y ->
                val Y = luma[y * width + x].toInt() and 0xFF
                val ci = (y / 2) * u.rowStride + (x / 2) * u.pixelStride
                val U = ub.get(ci).toInt() and 0xFF
                val V = vb.get((y / 2) * v.rowStride + (x / 2) * v.pixelStride).toInt() and 0xFF
                (Y shl 16) or (U shl 8) or V
            }
        }

        /** Grayscale upright thumbnail (for evidence frames). */
        fun thumbnail(maxSide: Int = 360): Bitmap {
            val step = maxOf(1, maxOf(width, height) / maxSide)
            val w = width / step; val h = height / step
            val upright = rotation == 90 || rotation == 270
            val ow = if (upright) h else w; val oh = if (upright) w else h
            val px = IntArray(ow * oh)
            for (y in 0 until h) for (x in 0 until w) {
                val g = luma[(y * step) * width + x * step].toInt() and 0xFF
                val (ox, oy) = when (rotation) {
                    90 -> (h - 1 - y) to x
                    180 -> (w - 1 - x) to (h - 1 - y)
                    270 -> y to (w - 1 - x)
                    else -> x to y
                }
                px[oy * ow + ox] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        }
    }

    fun read(image: ImageProxy): Frame {
        val w = image.width; val h = image.height
        val plane = image.planes[0]
        if (luma.size != w * h) luma = ByteArray(w * h)
        val buf = plane.buffer
        for (row in 0 until h) {
            buf.position(row * plane.rowStride)
            buf.get(luma, row * w, w)
        }
        val rot = image.imageInfo.rotationDegrees
        val t0 = System.nanoTime()
        val results = try {
            reader.decodeMultiple(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false))), hints)
        } catch (_: NotFoundException) {
            emptyArray()
        } finally {
            reader.reset()
        }
        val ms = (System.nanoTime() - t0) / 1e6
        val codes = results.mapNotNull { r ->
            val pts = r.resultPoints?.filterNotNull()?.map { rotate(it.x, it.y, w, h, rot) }.orEmpty()
            if (pts.isEmpty()) null else RawCode(r.text, pts)
        }
        val upright = rot == 90 || rot == 270
        val aspect = if (upright) h.toFloat() / w else w.toFloat() / h
        return Frame(codes, quality(luma, w, h), rot, w, h, aspect, ms, image, luma)
    }

    private fun rotate(x: Float, y: Float, w: Int, h: Int, rot: Int): Pt = when (rot) {
        90 -> Pt(1 - y / h, x / w)
        180 -> Pt(1 - x / w, 1 - y / h)
        270 -> Pt(y / h, 1 - x / w)
        else -> Pt(x / w, y / h)
    }

    private fun quality(l: ByteArray, w: Int, h: Int, step: Int = 4): FrameQuality {
        var sum = 0.0; var n = 0
        var lapSum = 0.0; var lapSq = 0.0; var m = 0
        var y = step
        while (y < h - step) {
            var x = step
            while (x < w - step) {
                val c = l[y * w + x].toInt() and 0xFF
                sum += c; n++
                val lap = 4 * c - (l[y * w + x - step].toInt() and 0xFF) - (l[y * w + x + step].toInt() and 0xFF) -
                    (l[(y - step) * w + x].toInt() and 0xFF) - (l[(y + step) * w + x].toInt() and 0xFF)
                lapSum += lap; lapSq += lap.toDouble() * lap; m++
                x += step
            }
            y += step
        }
        val mean = lapSum / m
        return FrameQuality(sum / n, lapSq / m - mean * mean)
    }
}
