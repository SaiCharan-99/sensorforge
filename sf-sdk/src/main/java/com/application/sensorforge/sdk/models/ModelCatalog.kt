package com.application.sensorforge.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Where a model's bytes come from. */
@Serializable
enum class ModelLocation {
    /** Bundled in the sf-sdk APK assets (`models/...`). */
    ASSET,
    /** Installed into app storage by the user (too large or licence-gated for the APK): `files/models/<file>`. */
    DEVICE,
    /** Code in this repo or a pinned library (no weights file); hashed from its version descriptor. */
    BUILTIN,
    /** Trained on the phone (heads); per pack, not a frozen base model. */
    HEAD,
    /** Android system service (TTS, on-device speech recognizer). */
    SYSTEM,
    /** Laptop-only tool, never on the phone. */
    LAPTOP,
}

/** One row of the model stack (model stack §1, §10). Pure data. */
@Serializable
data class ModelEntry(
    val id: String,
    /** Model-stack slot: M1, M2, H1, C1, ... */
    val slot: String,
    val name: String,
    val location: ModelLocation,
    /** Asset path, device file name, or builtin version descriptor. */
    val file: String? = null,
    val expectedSha256: String? = null,
    val bytes: Long? = null,
    val licence: String,
    val source: String,
    val runtime: String,
    val expectedBackend: String,
    val apps: List<String>,
    /** Perception profiles that need this model (models.lock membership). */
    val profiles: List<String> = emptyList(),
    val tier: String,
    /** Fallback id in this catalog, or a plain-language fallback. */
    val fallback: String,
    /** Claim when the fallback is in use (model stack §7). */
    val fallbackClaim: String,
    /** Higher wins when several entries fill the same slot + profile. */
    val priority: Int = 0,
)

@Serializable
enum class ModelStatus { INSTALLED, MISSING, HASH_MISMATCH, BUILTIN, NOT_TRAINED, TRAINED, OMITTED, UNAVAILABLE, LAPTOP_ONLY }

/** One frozen base model a pack depends on (tech spec §5 `models.lock`). */
@Serializable
data class LockEntry(
    val id: String,
    val version: String,
    val sha256: String,
    val runtime: String,
    @SerialName("expected_backend") val expectedBackend: String,
)

@Serializable
data class ModelsLock(
    @SerialName("schema_version") val schemaVersion: String = "3.0",
    val models: List<LockEntry>,
) {
    /** Stack id written into timelines: stable digest of the locked models. */
    fun stackId(): String = "lock:" + sha256(models.sortedBy { it.id }.joinToString("|") { "${it.id}@${it.sha256}" }).take(16)
}

/** A lock entry whose current hash differs, or whose model is gone (actual = null). */
data class LockMismatch(val id: String, val expected: String, val actual: String?)

object ModelCatalog {
    private val CAMERA_APPS = listOf("PackCheck", "SealSafe", "HandoverProof")

    val ENTRIES: List<ModelEntry> = listOf(
        ModelEntry(
            "m1_yoloe_11s", "M1", "YOLOE-11S open-vocab detector (TFLite export)", ModelLocation.DEVICE, "yoloe_11s.tflite",
            licence = "AGPL-3.0 (Ultralytics), hackathon only", source = "Ultralytics weights, exported on the laptop (tools/yoloe)",
            runtime = "LiteRT", expectedBackend = "QNN → GPU → CPU", apps = CAMERA_APPS,
            profiles = listOf("objects"), tier = "T1", fallback = "m1_effdet_lite2", fallbackClaim = "EfficientDet-Lite2 (COCO)", priority = 30,
        ),
        ModelEntry(
            "m1_effdet_lite2", "M1", "EfficientDet-Lite2 float32 (COCO)", ModelLocation.ASSET, "models/efficientdet_lite2.tflite",
            "ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d", 23_096_891,
            "Apache-2.0", "storage.googleapis.com/mediapipe-models", "MediaPipe Tasks 0.10.21 (TFLite)", "GPU → CPU",
            CAMERA_APPS, listOf("objects"), "T1", "m1_effdet_lite0", "EfficientDet-Lite0", 20,
        ),
        ModelEntry(
            "m1_effdet_lite0", "M1", "EfficientDet-Lite0 float32 (COCO)", ModelLocation.ASSET, "models/efficientdet_lite0.tflite",
            "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58", 13_836_895,
            "Apache-2.0", "storage.googleapis.com/mediapipe-models", "MediaPipe Tasks 0.10.21 (TFLite)", "GPU → CPU",
            CAMERA_APPS, listOf("objects"), "T1", "m1_tags", "controlled props", 10,
        ),
        ModelEntry(
            "m1_tags", "M1", "Tagged props (QR per prop, SF1 payloads)", ModelLocation.BUILTIN, "tags-qr/zxing-3.5.3/v1",
            licence = "Apache-2.0 (ZXing)", source = "in repo + com.google.zxing:core:3.5.3", runtime = "JVM", expectedBackend = "CPU",
            apps = CAMERA_APPS, profiles = listOf("objects"), tier = "T1",
            fallback = "none (declared fallback)", fallbackClaim = "controlled props", priority = 0,
        ),
        ModelEntry(
            "m2_mobilenet_v3", "M2", "MobileNet-V3-large image embedder", ModelLocation.ASSET, "models/mobilenet_v3_large.tflite",
            "11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d", 10_889_458,
            "Apache-2.0", "storage.googleapis.com/mediapipe-models", "MediaPipe Tasks 0.10.21 (TFLite)", "GPU → CPU",
            listOf("PackCheck", "SealSafe"), listOf("identity", "state"), "T1", "m2_yuv_hist", "handcrafted crop embedding", 10,
        ),
        ModelEntry(
            "m2_yuv_hist", "M2", "Handcrafted YUV-histogram crop embedding (24-d)", ModelLocation.BUILTIN, "yuv-hist-24/v1",
            licence = "in repo", source = "sf-sdk perception/CropEmbedding", runtime = "JVM", expectedBackend = "CPU",
            apps = listOf("PackCheck", "SealSafe"), profiles = listOf("identity", "state"), tier = "T1",
            fallback = "code read only", fallbackClaim = "no head claim", priority = 0,
        ),
        ModelEntry(
            "h1_identity", "H1", "Identity head (MLP on M2 embeddings)", ModelLocation.HEAD, "identity",
            licence = "trained on this phone", source = "Forge Train", runtime = "Kotlin", expectedBackend = "CPU",
            apps = listOf("PackCheck"), tier = "T1", fallback = "code read only", fallbackClaim = "no head claim for PackCheck",
        ),
        ModelEntry(
            "h2_state", "H2", "State head intact/broken (MLP on M2 embeddings)", ModelLocation.HEAD, "state",
            licence = "trained on this phone", source = "Forge Train", runtime = "Kotlin", expectedBackend = "CPU",
            apps = listOf("SealSafe"), tier = "T1", fallback = "state tags", fallbackClaim = "order + container only",
        ),
        ModelEntry(
            "h3_imu_risk", "H3", "IMU risk head (MLP on 36 window features)", ModelLocation.HEAD, "imu_risk",
            licence = "trained on this phone", source = "Forge Train", runtime = "Kotlin", expectedBackend = "CPU",
            apps = listOf("CrashGuard"), tier = "T1", fallback = "calibrated threshold + stillness", fallbackClaim = "rule-based triage",
        ),
        ModelEntry(
            "c1_zones_qr", "C1", "Zones from QR corner + floor markers (ArUco substitute)", ModelLocation.BUILTIN, "zones-qr/v1",
            licence = "in repo", source = "sf-sdk perception/Tags", runtime = "JVM", expectedBackend = "CPU",
            apps = CAMERA_APPS, profiles = listOf("zones"), tier = "T1", fallback = "none", fallbackClaim = "markers needed for zones",
        ),
        ModelEntry(
            "c1_quality", "C1", "Quality: luma + Laplacian variance, frame gaps → camera_unstable", ModelLocation.BUILTIN, "quality-lap/v1",
            licence = "in repo", source = "sf-sdk camera/TagFrameReader", runtime = "JVM", expectedBackend = "CPU",
            apps = CAMERA_APPS, profiles = listOf("quality"), tier = "T1", fallback = "none", fallbackClaim = "MOG2 unexplained_change not built",
        ),
        ModelEntry(
            "c2_zxing", "C2", "QR / barcode reader (ZXing core QRCodeMultiReader)", ModelLocation.BUILTIN, "zxing-core/3.5.3",
            licence = "Apache-2.0", source = "Maven Central com.google.zxing:core:3.5.3", runtime = "JVM", expectedBackend = "CPU",
            apps = listOf("PackCheck", "HandoverProof"), profiles = listOf("codes"), tier = "T1", fallback = "none", fallbackClaim = "—",
        ),
        ModelEntry(
            "c3_tracker", "C3", "Tracker: per-class association + alpha-beta filter, code re-id", ModelLocation.BUILTIN, "tracker-ab/v1",
            licence = "in repo", source = "sf-sdk perception/Tracker", runtime = "JVM", expectedBackend = "CPU",
            apps = CAMERA_APPS, profiles = listOf("objects"), tier = "T1", fallback = "none", fallbackClaim = "—", priority = -1,
        ),
        ModelEntry(
            "m3_pose_heavy", "M3", "MediaPipe Pose Landmarker (heavy, float16)", ModelLocation.ASSET, "models/pose_landmarker_heavy.task",
            "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b", 30_664_242,
            "Apache-2.0", "storage.googleapis.com/mediapipe-models", "MediaPipe Tasks 0.10.21", "GPU → CPU",
            listOf("RepRight"), listOf("body"), "T2", "drop RepRight", "—",
        ),
        ModelEntry(
            "m4_hands", "M4", "MediaPipe Hand Landmarker (float16)", ModelLocation.ASSET, "models/hand_landmarker.task",
            "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1", 7_819_105,
            "Apache-2.0", "storage.googleapis.com/mediapipe-models", "MediaPipe Tasks 0.10.21", "GPU → CPU",
            listOf("HandoverProof"), listOf("hands"), "T2", "side zones only", "rule-based hold detection",
        ),
        ModelEntry(
            "m5_relate_anything", "M5", "RelateAnything relations (keyframes, spike)", ModelLocation.DEVICE, "relate_anything.onnx",
            licence = "Apache-2.0 code; DINOv3 weights Meta licence-gated", source = "github.com/Maelic/RelateAnything",
            runtime = "ONNX Runtime Android", expectedBackend = "GPU/CPU", apps = listOf("HandoverProof"),
            tier = "T2 spike", fallback = "side zones (hand overlap)", fallbackClaim = "rule-based hold detection",
        ),
        ModelEntry(
            "m6_smollm2", "M6", "SmolLM2-1.7B-Instruct Q4 GGUF (authoring only)", ModelLocation.DEVICE, "smollm2-1.7b-instruct-q4_k_m.gguf",
            licence = "Apache-2.0", source = "Hugging Face HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF", runtime = "llama.cpp (llamacpp-kotlin 0.4.0, Apache-2.0)",
            expectedSha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            expectedBackend = "CPU", apps = listOf("Forge"), tier = "P1", fallback = "template compiler", fallbackClaim = "guided authoring",
        ),
        ModelEntry(
            "m7_stt", "M7", "Android on-device speech recognizer", ModelLocation.SYSTEM, "android.speech on-device",
            licence = "system", source = "Android SpeechRecognizer (offline language pack)", runtime = "Android", expectedBackend = "system",
            apps = listOf("Forge"), tier = "P1", fallback = "typed sentence", fallbackClaim = "—",
        ),
        ModelEntry(
            "tts", "TTS", "Android TextToSpeech (coaching)", ModelLocation.SYSTEM, "android.speech.tts",
            licence = "system", source = "Android", runtime = "Android", expectedBackend = "system",
            apps = listOf("PackCheck", "SealSafe", "RepRight", "HandoverProof"), tier = "T1",
            fallback = "on-screen coaching only", fallbackClaim = "—",
        ),
        ModelEntry(
            "o1_laya", "O1", "Laya step-typer (optional)", ModelLocation.DEVICE, "laya_int8.onnx",
            licence = "unknown (no source given)", source = "not provided", runtime = "ONNX Runtime", expectedBackend = "CPU",
            apps = listOf("Forge"), tier = "Optional", fallback = "omit", fallbackClaim = "—",
        ),
        ModelEntry(
            "o2_tapnext", "O2", "TAPNext++ (laptop example labelling)", ModelLocation.LAPTOP, null,
            licence = "Apache-2.0", source = "github.com/google-deepmind/tapnet", runtime = "laptop GPU", expectedBackend = "laptop",
            apps = listOf("Forge (offline prep)"), tier = "Optional", fallback = "omit", fallbackClaim = "—",
        ),
    )

    fun byId(id: String) = ENTRIES.first { it.id == id }

    /** Hash of a builtin implementation: its pinned version descriptor. */
    fun builtinSha256(e: ModelEntry): String = sha256("builtin:${e.id}:${e.file}")

    /**
     * models.lock for a pack (tech spec §5): exactly the frozen base models the runtime
     * uses for it ([ids], chosen by the perception plan). [current] maps id → sha256.
     */
    fun lockOf(ids: Collection<String>, current: Map<String, String?>): ModelsLock {
        val missing = ids.filter { current[it] == null }
        require(missing.isEmpty()) { "models not available: $missing" }
        return ModelsLock(models = ids.distinct().sorted().map { id ->
            val e = byId(id)
            require(e.location in FROZEN) { "$id is not a frozen base model" }
            LockEntry(e.id, e.file ?: e.id, current.getValue(id)!!, e.runtime, e.expectedBackend)
        })
    }

    /** Fail-closed check (tech spec §5): every locked model must be present with the same hash. */
    fun check(lock: ModelsLock, current: Map<String, String?>): List<LockMismatch> =
        lock.models.mapNotNull { l -> current[l.id].let { if (it != l.sha256) LockMismatch(l.id, l.sha256, it) else null } }

    private val FROZEN = setOf(ModelLocation.ASSET, ModelLocation.DEVICE, ModelLocation.BUILTIN)
}

internal fun sha256(s: String): String = sha256(s.toByteArray())
fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
