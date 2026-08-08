import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// 读取签名配置（keystore.properties 不入库，避免密钥泄露）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.peng.ainewshub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peng.ainewshub"
        minSdk = 24
        targetSdk = 35
        // 版本号默认 1.0(1),release.yml 发版时从 tag 经 -PversionName/-PversionCode 注入
        versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = findProperty("versionName") as? String ?: "1.0"
    }

    signingConfigs {
        create("release") {
            // storeFile 路径相对工程根目录解析（properties 中写的是 root 相对路径）
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile") ?: "release.jks")
            storePassword = keystoreProperties.getProperty("storePassword") ?: ""
            keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
            keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
        }
    }

    buildTypes {
        // debug 加 .debug 后缀,可与正式包共存于同一手机;resValue 覆盖 app_name 以区分图标标签
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "AI News Hub (Debug)")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jsoup)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.reorderable)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}
