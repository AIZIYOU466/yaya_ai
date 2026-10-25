import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// 复用 yaya 主项目的 theme/components 源码（纯 Compose，无 Android 依赖）
kotlin.sourceSets.getByName("main").kotlin.srcDir("../android/app/src/main/kotlin/com/yaya/ai/ui/theme")
kotlin.sourceSets.getByName("main").kotlin.srcDir("../android/app/src/main/kotlin/com/yaya/ai/ui/components")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

compose.desktop {
    application {
        mainClass = "PreviewKt"
    }
}
