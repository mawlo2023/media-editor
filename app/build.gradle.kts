import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.mawlo.mediaeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.mawlo.mediaeditor"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Optionaler eigener Schlüssel: keystore.properties im Projektordner
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val p = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

val checkModel by tasks.registering {
    doLast {
        val f = file("src/main/assets/lama_fp32.onnx")
        if (!f.exists()) {
            throw GradleException("KI-Modell fehlt. Bitte zuerst scripts/download_model.sh ausführen.")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkModel) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
