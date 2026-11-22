// 캠뉴런 라이브: PC 없이 폰에서 치지직으로 바로 RTMP 송출. 카메라·인코더·발열 조절은 이 프로젝트의 core 모듈에 있다
// (캠뉴런에서 복사해 와 따로 관리한다). 출시하지 않고 혼자 쓰는 앱이라 디버그 빌드를 폰에 깔아 쓴다.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camneuron.live"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.camneuron.live"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // 쓰지 않는다 (출시하지 않는다). 서명 키도 없다.
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
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    // 단위 테스트의 android.jar에는 org.json 구현이 없어 실제 구현을 넣는다 (치지직 API 응답 해석 테스트).
    testImplementation("org.json:json:20240303")
}
