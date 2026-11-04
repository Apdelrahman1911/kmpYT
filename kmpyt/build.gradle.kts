import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    `maven-publish`
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    val framework = XCFramework("KmpYT")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "KmpYT"
            isStatic = true
            framework.add(this)
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("io.ktor:ktor-client-core:3.2.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.2.3")
        }
        val jvmAndroidMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmAndroidMain)
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:3.2.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.2.3")
            implementation("io.ktor:ktor-client-encoding:3.2.3")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.2.3") }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test:core:1.6.1")
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "dev.kmpyt"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosSimulatorDevice").orNull?.let { device.set(it) }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name.set("kmpYT")
        description.set("YouTube inspection, browsing, captions and downloads for Kotlin Multiplatform.")
        url.set("https://github.com/Apdelrahman1911/kmpYT")
        scm {
            url.set("https://github.com/Apdelrahman1911/kmpYT")
            connection.set("scm:git:https://github.com/Apdelrahman1911/kmpYT.git")
        }
    }
}
