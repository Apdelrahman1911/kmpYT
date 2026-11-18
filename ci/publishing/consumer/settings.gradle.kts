pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

val candidateDirectory = providers.gradleProperty("kmpytCandidateDirectory").orNull
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (candidateDirectory != null) {
            exclusiveContent {
                forRepository { maven { url = uri(candidateDirectory) } }
                filter { includeGroup("io.github.apdelrahman1911.kmpyt") }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "kmpyt-release-consumer"
