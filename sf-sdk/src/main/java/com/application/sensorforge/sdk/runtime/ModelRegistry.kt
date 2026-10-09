package com.application.sensorforge.sdk.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.SpeechRecognizer
import com.application.sensorforge.sdk.models.LockMismatch
import com.application.sensorforge.sdk.models.ModelCatalog
import com.application.sensorforge.sdk.models.ModelEntry
import com.application.sensorforge.sdk.models.ModelLocation
import com.application.sensorforge.sdk.models.ModelStatus
import com.application.sensorforge.sdk.models.ModelsLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Live state of one model-stack entry on this phone. */
data class ModelState(
    val entry: ModelEntry,
    val status: ModelStatus,
    val sha256: String?,
    /** Path or asset where it lives, if any. */
    val where: String?,
    val note: String = "",
) {
    /** Usable as a frozen base model (models.lock member). */
    val usable get() = status == ModelStatus.INSTALLED || status == ModelStatus.BUILTIN
}

/**
 * Model registry (model stack §8/§10, tech spec §5): every model-stack slot with file,
 * sha256, licence, runtime, expected backend and status. Asset hashes are computed
 * once per APK install and cached.
 */
class ModelRegistry private constructor(private val context: Context) {
    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val cacheFile = File(modelsDir, ".hashcache")
    private val hashCache = mutableMapOf<String, String>()
    private val apkStamp: Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    init {
        if (cacheFile.exists()) cacheFile.readLines().forEach { l ->
            val p = l.split(' ')
            if (p.size == 3 && p[0] == apkStamp.toString()) hashCache[p[1]] = p[2]
        }
    }

    fun states(): List<ModelState> = ModelCatalog.ENTRIES.map { state(it) }

    fun state(id: String): ModelState = state(ModelCatalog.byId(id))

    fun state(e: ModelEntry): ModelState = when (e.location) {
        ModelLocation.ASSET -> {
            val h = runCatching { assetSha(e.file!!) }.getOrNull()
            when {
                h == null -> ModelState(e, ModelStatus.MISSING, null, "asset:${e.file}", "not bundled; fallback ${e.fallback}")
                e.expectedSha256 != null && h != e.expectedSha256 -> ModelState(e, ModelStatus.HASH_MISMATCH, h, "asset:${e.file}", "hash differs from model stack §10")
                else -> ModelState(e, ModelStatus.INSTALLED, h, "asset:${e.file}")
            }
        }
        ModelLocation.DEVICE -> {
            val f = File(modelsDir, e.file!!)
            if (!f.exists() || f.length() == 0L) {
                val status = if (e.id == "o1_laya") ModelStatus.OMITTED else ModelStatus.MISSING
                ModelState(e, status, null, f.absolutePath, "not installed; fallback: ${e.fallback} (claim: ${e.fallbackClaim})")
            } else {
                val h = fileSha(f)
                if (e.expectedSha256 != null && h != e.expectedSha256) ModelState(e, ModelStatus.HASH_MISMATCH, h, f.absolutePath)
                else ModelState(e, ModelStatus.INSTALLED, h, f.absolutePath, "${f.length() / 1_000_000} MB")
            }
        }
        ModelLocation.BUILTIN -> ModelState(e, ModelStatus.BUILTIN, ModelCatalog.builtinSha256(e), "builtin:${e.file}")
        ModelLocation.HEAD -> {
            val heads = File(context.filesDir, "heads").listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(e.file!!) }
            val active = heads.filter { File(it, "active.txt").exists() }
            when {
                active.isNotEmpty() -> ModelState(e, ModelStatus.TRAINED, null, active.joinToString { it.name }, "approved: ${active.joinToString { it.name }}")
                heads.any { d -> d.listFiles().orEmpty().any { it.name.startsWith("v") } } ->
                    ModelState(e, ModelStatus.NOT_TRAINED, null, null, "trained version not approved yet; fallback ${e.fallback}")
                else -> ModelState(e, ModelStatus.NOT_TRAINED, null, null, "no examples trained yet; fallback ${e.fallback}")
            }
        }
        ModelLocation.SYSTEM -> systemState(e)
        ModelLocation.LAPTOP -> ModelState(e, ModelStatus.LAPTOP_ONLY, null, null, "laptop-side labelling only; no phone claim")
    }

    private fun systemState(e: ModelEntry): ModelState = when (e.id) {
        "tts" -> {
            val engines = context.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            if (engines.isNotEmpty()) ModelState(e, ModelStatus.INSTALLED, null, engines.first().serviceInfo.packageName)
            else ModelState(e, ModelStatus.UNAVAILABLE, null, null, "no TTS engine; coaching is on-screen only")
        }
        "m7_stt" -> {
            val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if (onDevice) ModelState(e, ModelStatus.INSTALLED, null, "on-device recognizer", "offline only if the language pack is installed")
            else ModelState(e, ModelStatus.UNAVAILABLE, null, null, "no on-device recognizer; type the sentence instead")
        }
        else -> ModelState(e, ModelStatus.UNAVAILABLE, null, null)
    }

    /** id → current sha256 for every usable frozen base model. */
    fun currentHashes(): Map<String, String?> = states().associate { it.entry.id to (if (it.usable) it.sha256 else null) }

    fun lockOf(ids: Collection<String>): ModelsLock = ModelCatalog.lockOf(ids, currentHashes())

    fun check(lock: ModelsLock): List<LockMismatch> = ModelCatalog.check(lock, currentHashes())

    /** Path of an installable DEVICE model (exists or not). */
    fun deviceFile(id: String): File = File(modelsDir, ModelCatalog.byId(id).file!!)

    @Synchronized
    private fun assetSha(path: String): String = hashCache.getOrPut(path) {
        context.assets.open(path).use { digest(it) }.also { cacheFile.appendText("$apkStamp $path $it\n") }
    }

    @Synchronized
    private fun fileSha(f: File): String = hashCache.getOrPut("${f.absolutePath}@${f.length()}@${f.lastModified()}") { f.inputStream().use { digest(it) } }

    private fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var instance: ModelRegistry? = null
        fun get(context: Context): ModelRegistry = instance ?: synchronized(this) {
            instance ?: ModelRegistry(context.applicationContext).also { instance = it; BackendLog.init(context) }
        }
    }
}
