pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "openhu-companion"
include(":app")
// extension apps (openhu-companion#3): the client library and the official extensions
include(":extensions:client")
include(":extensions:vehicle")
