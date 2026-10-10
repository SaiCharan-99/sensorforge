plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.application.sensorforge.sdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.lifecycle)
    api(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.llamacpp.kotlin)
    implementation(libs.onnxruntime.android)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
