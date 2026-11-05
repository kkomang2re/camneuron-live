// 캠뉴런과 야외 방송 앱이 같이 쓰는 부분: 카메라, 영상·소리 압축, 뷰티 보정, 채팅·후원 창, 발열·네트워크 조절.
// OBS 연결(프로토콜·페어링·원격 조작)은 app 모듈에만 둔다.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camneuron.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        // app의 beta 빌드가 이 모듈의 release를 쓴다 (app의 matchingFallbacks).
        release {
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
