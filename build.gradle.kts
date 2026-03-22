import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.yourcompany"
version = "1.0.0"

repositories {
    mavenCentral()
}

// IntelliJ Platform Plugin 설정
// since-build/until-build 범위를 넓게 설정해서 구버전 IntelliJ에서도 동작
intellij {
    version.set("2021.1")          // 최소 지원 버전 (2021.1+)
    type.set("IC")                 // IntelliJ IDEA Community
    plugins.set(listOf("com.intellij.java"))  // Java PSI 사용 필수
    updateSinceUntilBuild.set(false)          // 버전 범위 제한 해제
}

dependencies {
    // XML 생성용 (JDK 내장 사용 - 추가 의존성 없음)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "8"   // 레거시 시스템 Java 8 대응
        targetCompatibility = "8"
    }

    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "8"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    patchPluginXml {
        sinceBuild.set("211")       // IntelliJ 2021.1
        untilBuild.set("")          // 상한 없음 (최신 버전까지 지원)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN") ?: "")
        privateKey.set(System.getenv("PRIVATE_KEY") ?: "")
        password.set(System.getenv("PRIVATE_KEY_PASSWORD") ?: "")
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN") ?: "")
    }
}
