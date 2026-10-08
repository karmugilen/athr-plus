plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.ather.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.ather.pro"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("athrVersionCode").orNull?.toInt() ?: 20
        versionName = providers.gradleProperty("athrVersionName").orNull ?: "1.1.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A release must use the same private signing key as the existing public APK.
    val releaseKey = providers.environmentVariable("ATHR_SIGNING_STORE").orNull
    signingConfigs {
        create("publisher") {
            if (releaseKey != null) {
                storeFile = file(releaseKey)
                storePassword = providers.environmentVariable("ATHR_SIGNING_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ATHR_SIGNING_ALIAS").orNull
                keyPassword = providers.environmentVariable("ATHR_SIGNING_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("publisher")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Ather WebSocket API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.40")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}

// Native computation is packaged for physical phones and emulators.
val nativeOutput = layout.buildDirectory.dir("generated/rustJniLibs")
val buildRust by tasks.registering(Exec::class) {
    inputs.files(fileTree("../../rust/ather-math") { exclude("target/**") })
    inputs.file("../../scripts/build-android-rust.sh")
    outputs.dir(nativeOutput)
    doFirst { delete(nativeOutput.get().asFile) }
    environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
    commandLine("bash", file("../../scripts/build-android-rust.sh").absolutePath,
        nativeOutput.get().asFile.absolutePath)
}
android.sourceSets.getByName("main").jniLibs.srcDir(nativeOutput)
tasks.named("preBuild").configure { dependsOn(buildRust) }

// Fail before producing an unsigned or differently keyed release by accident.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        require(!System.getenv("ATHR_SIGNING_STORE").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_ALIAS").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_STORE_PASSWORD").isNullOrBlank() &&
            !System.getenv("ATHR_SIGNING_KEY_PASSWORD").isNullOrBlank()) {
            "Configure the original release signing key first; see docs/APP-UPDATES.md."
        }
    }
}
