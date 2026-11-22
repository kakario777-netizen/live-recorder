plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kakario.liverecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.liverecorder"
        minSdk = 29
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 업데이트되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // 고정 디버그 키 — 빌드마다 서명이 바뀌어 재설치가 필요해지는 문제 방지
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // 녹화 파일을 폰 하드웨어 인코더로 H.265 변환
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    // 구글 로그인 차단 피하기 (앱 이름 머리말 끄기)
    implementation("androidx.webkit:webkit:1.12.1")
    // NAS(FTP) 자동 업로드
    implementation("commons-net:commons-net:3.11.1")
}
