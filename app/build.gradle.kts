plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 실험판(LAB=1): 원래 앱과 따로 설치되는 테스트용 (이름·저장 폴더·업데이트도 따로)
val lab = System.getenv("LAB") == "1"

android {
    namespace = "com.kakario.liverecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = if (lab) "com.kakario.liverecorder.lab" else "com.kakario.liverecorder"
        resValue("string", "app_name", if (lab) "라이브 녹화 실험" else "라이브 녹화")
        buildConfigField("boolean", "LAB", lab.toString())
        minSdk = 29
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 업데이트되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // 고정 디버그 키 — 빌드마다 서명이 바뀌어 재설치가 필요해지는 문제 방지
        getByName("debug") {
            // 서명 키는 GitHub Secrets에서 빌드 때만 꺼내 씀 (저장소에는 두지 않음)
            storeFile = file(System.getenv("SIGNING_KEYSTORE_PATH") ?: "debug.keystore")
            storePassword = (System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android")
            keyAlias = "androiddebugkey"
            keyPassword = (System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

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
    // 채팅 직접 받기(웹소켓)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 즐겨찾기 바뀜 알림(파이어베이스 푸시)
    implementation("com.google.firebase:firebase-messaging:24.1.0")
}
