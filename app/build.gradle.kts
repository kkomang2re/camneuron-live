// 캠뉴런 라이브: PC 없이 폰에서 치지직으로 바로 RTMP 송출. 카메라·인코더·발열 조절은 이 프로젝트의 core 모듈에 있다
// (캠뉴런에서 복사해 와 따로 관리한다). 이름·패키지·서명 키는 임시다 (출시 전에 정한다).
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
            // 서명 키는 아직 없다. 출시 준비 때 app 모듈처럼 keystore.properties로 붙인다.
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
