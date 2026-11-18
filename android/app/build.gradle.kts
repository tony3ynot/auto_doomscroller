import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The frame encoder comes from `python -m autodoom.quantize` and is not in git. A build made
// without it works, it just makes no skip predictions.
val frameEncoder = layout.buildDirectory.dir("frame-encoder")
val copyFrameEncoder by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("../ml/models")) { include("encoder_fp16.tflite") }
    into(frameEncoder)
}
tasks.named("preBuild") { dependsOn(copyFrameEncoder) }

android {
    namespace = "com.autodoomscroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autodoomscroller"
        // 30 = Android 11, the first version with AccessibilityService.takeScreenshot().
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(frameEncoder)
    // Stored uncompressed so the encoder can be memory-mapped.
    androidResources { noCompress += "tflite" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub in unit tests; this is the real one.
    testImplementation(libs.json)
}

// MediaPipe models for hands-free mode. Downloaded on the first build instead of committed;
// pinned to version 1 so a rebuild gets the same files.
val mediaPipeModels = mapOf(
    "face_landmarker.task" to
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
    "gesture_recognizer.task" to
        "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task",
)
val downloadMediaPipeModels by tasks.registering {
    val assets = layout.projectDirectory.dir("src/main/assets")
    outputs.files(mediaPipeModels.keys.map { assets.file(it) })
    doLast {
        mediaPipeModels.forEach { (name, url) ->
            val target = assets.file(name).asFile
            if (!target.exists()) {
                target.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadMediaPipeModels) }
