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

        // 서버·폰 웹 주소 (car-app-design.md 2장). 배포처가 정해지면 바꿈
        // 에뮬레이터에서 PC의 localhost는 10.0.2.2
        buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:8080\"")
        buildConfigField("String", "WEB_URL", "\"http://localhost:5173\"")

        // 휴게소 지도(카카오맵 JS)용 키. git에 올리지 않는 car-app/local.properties의 KAKAO_JS_KEY (team-setup.md)
        // 없으면 지도 대신 정보 목록만 보여 준다
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "KAKAO_JS_KEY", "\"${localProps.getProperty("KAKAO_JS_KEY", "")}\"")
    }

    buildTypes {
        release {
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
