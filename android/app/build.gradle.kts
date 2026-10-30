plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.livehub.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livehub.tv"
        // Android TV 5.0 (API 21) 是最低面，覆盖 2014 年至今的全部 Android TV / Google TV 设备
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Android TV 专用组件（焦点、卡片、TabRow 的 TV 变体）
    implementation("androidx.tv:tv-material:1.0.0")

    // 播放内核：Media3 / ExoPlayer。FLV 由 media3-extractor 内置解析，HLS 由 exoplayer-hls 提供
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // 与 okhttp 同版本：`Net` 的错误码映射和「虎牙重试一次」只能靠真 HTTP 服务端验，
    // 编译器和 review 都看不出退化（catch 落空在 Kotlin 里完全合法，没有 warning）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
