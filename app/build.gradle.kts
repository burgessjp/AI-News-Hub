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

// 根 CHANGELOG.md 是唯一真相源：构建时自动拷入 generated assets 目录（不落 src 树、无需
// gitignore），App 内「更新日志」页（ui/more/ChangelogScreen.kt）读取渲染。发版流程不变，
// 改完 CHANGELOG.md 重新打包即带上最新内容。
// 注：srcDir 挂 Provider 不携带 task 依赖（实测 mergeAssets 不会触发拷贝），故显式挂 preBuild。
val changelogAssetsDir = layout.buildDirectory.dir("generated/changelog")
val syncChangelogAssets = tasks.register<Copy>("syncChangelogAssets") {
    from(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    into(changelogAssetsDir)
}
// preBuild 早于所有 variant 的 mergeAssets，保证拷贝先于打包发生
tasks.named("preBuild") { dependsOn(syncChangelogAssets) }

android {
    namespace = "com.peng.ainewshub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peng.ainewshub"
        minSdk = 24
        targetSdk = 35
        // 版本号默认 1.2.13(10213),发版时同步此兜底值;release.yml 从 tag 经 -PversionName/-PversionCode 注入
        versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 10213
        versionName = findProperty("versionName") as? String ?: "1.2.13"
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

    // CHANGELOG.md 经 syncChangelogAssets 拷入 generated 目录后并入主 assets
    sourceSets.getByName("main") {
        assets.srcDir(changelogAssetsDir)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric 需要读取资源/资产(R.string、assets 下的 readability.js 等)
            isIncludeAndroidResources = true
        }
    }
}

// Room schema 导出:自 v5 起提交 app/schemas/,是后续迁移测试(MigrationTestHelper)的前提
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.reorderable)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.org.json)
}
