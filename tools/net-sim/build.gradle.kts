// 앱의 실제 송출 코드(rtmp 패키지, NetGovernor, LiveNet)를 PC에서 돌려, 느려졌다 빨라지는 망에서 영상이 서버에
// 얼마나 늦게 닿는지 잰다. Android SDK는 필요 없다 (이 코드들은 Android에 의존하지 않는다).
//   gradlew -p tools/net-sim installDist
//   tools/net-sim/build/install/net-sim/bin/net-sim walk out.csv
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

val repo = rootDir.resolve("../..")

sourceSets {
    main {
        kotlin {
            srcDir("src")
            srcDir(repo.resolve("app/src/main/java"))
            srcDir(repo.resolve("core/src/main/java"))
            include(
                "sim/**",
                "com/camneuron/live/rtmp/**",
                "com/camneuron/live/LiveNet.kt",
                "com/camneuron/core/net/**",
                "com/camneuron/core/device/HeatGovernor.kt",
            )
        }
    }
}

application {
    mainClass.set("sim.SimKt")
}
