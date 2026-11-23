// 캠뉴런 라이브: PC 없이 폰에서 치지직으로 바로 RTMP 송출. 카메라·인코더·발열 조절은 이 프로젝트의 core 모듈에 있다
// (캠뉴런에서 복사해 와 따로 관리한다). 스토어에 내지 않고 혼자 쓰는 앱이다. 디버그 빌드(com.camneuron.live.debug)로
// 시험하고, 폰에 두고 쓰는 것은 서명한 릴리즈 빌드(com.camneuron.live)다. 둘은 다른 앱이라 함께 깔린다.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 릴리즈 서명 키. 저장소에 넣지 않는다 (.gitignore). 루트의 keystore.properties가 있을 때만 서명한다:
//   storeFile=keystore/camneuron-live-release.jks  storePassword=…  keyAlias=…  keyPassword=…
// 키를 잃으면 폰에 깔린 릴리즈 앱 위에 새 버전을 덮어 깔 수 없다 (지우고 다시 깔아야 하고, 그러면 폰에 저장한 설정도 지워진다).
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.camneuron.live"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.camneuron.live"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.0.5"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // 줄이기(R8)는 켜지 않는다: 혼자 쓰는 앱이라 크기보다 디버그 빌드와 똑같이 도는 것이 중요하다.
            isMinifyEnabled = false
            // keystore.properties가 없으면 서명하지 않은 APK가 나온다 (폰에 깔 수 없다).
            signingConfig = signingConfigs.findByName("release")
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
