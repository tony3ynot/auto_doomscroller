import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Times the frame encoder builds on a phone (scripts/benchmark-encoder.sh). It is a module
// of its own, with nothing but an instrumented test, so that running it leaves the app, its
// data and its accessibility service alone.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The encoder builds come from `python -m autodoom.quantize` and are not in git.
val encoders = layout.buildDirectory.dir("encoders")
val copyEncoders by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("../ml/models")) { include("encoder_*.tflite") }
    into(encoders)
}
tasks.named("preBuild") { dependsOn(copyEncoders) }

android {
    namespace = "com.autodoomscroller.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The runtime reports a TensorFlow version of its own, so the release goes in with it.
        buildConfigField("String", "LITERT_VERSION", "\"${libs.versions.litert.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Without this the test APK targets minSdk, and Play Protect blocks installing an app
    // built for a version that old.
    testOptions { targetSdk = 36 }

    sourceSets["androidTest"].assets.srcDir(encoders)
    // Stored uncompressed so the models can be memory-mapped, as the app will load them.
    androidResources { noCompress += "tflite" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    androidTestImplementation(libs.litert)
    androidTestImplementation(libs.litert.gpu)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
