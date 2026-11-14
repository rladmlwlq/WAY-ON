import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wayon.car"
    compileSdk = 35
    // 주행 규제 CarUxRestrictionsManager (Pleos 주행 규제 개발 가이드). 차 시스템에 있는 라이브러리라 APK에 넣지 않음
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.wayon.car"
        // Pleos 출품 앱 필수 요건 (서명 키 로테이션·리니지 지원)
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 휴게소 지도(카카오맵 JS)용 키. git에 올리지 않는 car-app/local.properties의 KAKAO_JS_KEY (team-setup.md)
        // 없으면 지도 대신 정보 목록만 보여 준다
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }

        // 서버·폰 웹 주소 (car-app-design.md 2장). 기본은 에뮬레이터 로컬 개발 (PC의 localhost = 10.0.2.2).
        // 서버를 배포하면 local.properties의 SERVER_URL·WEB_URL로 바꿔 빌드한다 (README)
        val serverUrl = localProps.getProperty("SERVER_URL", "http://10.0.2.2:8080")
        val webUrl = localProps.getProperty("WEB_URL", "http://localhost:5173")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "WEB_URL", "\"$webUrl\"")
        buildConfigField("String", "KAKAO_JS_KEY", "\"${localProps.getProperty("KAKAO_JS_KEY", "")}\"")
    }

    // 릴리스 서명: git에 올리지 않는 car-app/keystore.properties + 키 파일 (team-setup.md, README).
    // 없으면 서명 없이 빌드된다 (설치 안 됨)
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}

// 공모전 제출 위치·이름 (팀명_앱이름_release.apk, 저장소 루트 apk/). gradlew assembleRelease 뒤에 복사
val copyReleaseApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.file("../apk"))
    rename { "WayOn_WayOn_release.apk" }
}
afterEvaluate { tasks.named("assembleRelease") { finalizedBy(copyReleaseApk) } }
