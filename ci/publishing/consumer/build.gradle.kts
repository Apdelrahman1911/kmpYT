import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.android.library") version "8.13.2"
}

val releaseVersion = providers.gradleProperty("kmpytReleaseVersion").get()

kotlin {
    androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets.commonMain.dependencies {
        implementation("io.github.apdelrahman1911.kmpyt:kmpyt:$releaseVersion")
    }
}

android {
    namespace = "dev.kmpyt.releaseconsumer"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
