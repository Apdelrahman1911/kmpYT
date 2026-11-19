plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.android.library") version "8.13.2" apply false
}
allprojects {
    group = "io.github.apdelrahman1911.kmpyt"
    version = providers.gradleProperty("kmpyt.version").orElse("0.1.8-SNAPSHOT").get()
}
