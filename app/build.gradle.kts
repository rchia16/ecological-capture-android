import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}

val firebaseTransfer = providers.gradleProperty("firebaseTransfer").orNull == "true"
val gatewayTransfer = providers.gradleProperty("gatewayTransfer").orNull == "true"
require(!(firebaseTransfer && gatewayTransfer)) { "Choose one live transfer backend" }
val gatewayEndpoint = localProperties.getProperty("gateway_api_endpoint",
    "https://promaxgb10-4e2b.tail06a565.ts.net/v2")
require(gatewayEndpoint.matches(Regex("https://[A-Za-z0-9.-]+/v2"))) { "Gateway endpoint must be a fixed HTTPS /v2 origin" }
if (gatewayTransfer) require(file("libs/ecocrypto.aar").isFile) { "Build crypto/android-age first" }
val firebaseConfig = Properties().apply {
    val config = rootProject.file("firebase/android.local.properties")
    if (firebaseTransfer) {
        require(config.isFile) { "Copy firebase/android.example.properties to firebase/android.local.properties first" }
        config.inputStream().use(::load)
        require(file("libs/ecocrypto.aar").isFile) { "Build crypto/android-age first" }
    }
}
fun firebaseValue(key: String): String = firebaseConfig.getProperty(key, "").also {
    require(!it.contains('"') && !it.contains('\\') && !it.contains('\n'))
    if (firebaseTransfer) require(it.isNotBlank() && !it.contains("PROJECT_ID")) { "Configure Firebase $key" }
}

android {
    namespace = "com.rchia.ecocapture.phase0"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    ndkPath = rootProject.file("tools/vlm-feasibility/artifacts/android-ndk-r28c").absolutePath

    defaultConfig {
        applicationId = "com.rchia.ecocapture.phase0"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"
        buildConfigField("boolean", "FIREBASE_TRANSFER_ENABLED", firebaseTransfer.toString())
        buildConfigField("boolean", "GATEWAY_TRANSFER_ENABLED", gatewayTransfer.toString())
        buildConfigField("String", "GATEWAY_API_ENDPOINT", "\"$gatewayEndpoint\"")
        manifestPlaceholders["firebaseTransferEnabled"] = firebaseTransfer.toString()
        manifestPlaceholders["gatewayTransferEnabled"] = gatewayTransfer.toString()
        for (key in listOf("API_KEY", "APPLICATION_ID", "PROJECT_ID", "STORAGE_BUCKET", "API_ENDPOINT"))
            buildConfigField("String", "FIREBASE_$key", "\"${firebaseValue(key)}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                // Inference comparisons require optimized native code in engineering/debug builds too.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                targets += "ecocapture_vlm"
            }
        }

        manifestPlaceholders["mwdat_application_id"] =
            localProperties.getProperty("mwdat_application_id", "")
        manifestPlaceholders["mwdat_client_token"] =
            localProperties.getProperty("mwdat_client_token", "")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Opt-in benchmark package keeps models, scheduling and results isolated from research data.
            if (providers.gradleProperty("vlmBenchmark").orNull == "true") {
                applicationIdSuffix = ".benchmark"
            }
        }
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
    if (firebaseTransfer) {
        sourceSets.getByName("main").java.srcDir("src/firebase/java")
    }
    if (gatewayTransfer) sourceSets.getByName("main").java.srcDir("src/gateway/java")
    // Disposable, generated test videos; prepare with tools/prepare-frame-sampler-fixtures.ps1.
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/checkpoint5-fixtures"))
    // Disposable, generated test videos; prepare with tools/prepare-frame-sampler-fixtures.ps1.
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/checkpoint5-fixtures"))
    externalNativeBuild {
        cmake {
            path = rootProject.file("vlm-native/src/main/cpp/CMakeLists.txt")
            version = "3.29.2"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    if (gatewayTransfer) implementation(files("libs/ecocrypto.aar"))
    if (firebaseTransfer) {
        implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
        implementation("com.google.firebase:firebase-auth")
        implementation("com.google.firebase:firebase-storage")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
        implementation(files("libs/ecocrypto.aar"))
    }
    implementation("androidx.work:work-runtime:2.11.2")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
