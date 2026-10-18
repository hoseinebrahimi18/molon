pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.9.2"
        id("com.android.library") version "8.9.2"
        id("org.jetbrains.kotlin.android") version "2.1.10"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenSSTP-Android"

val softEtherModuleDir = file("vendor/SoftEther-Android-Module")
if (!softEtherModuleDir.isDirectory) {
    throw GradleException("Native SoftEther source is missing. Run scripts/bootstrap-softether.sh (or .ps1) from the repository root, then build again.")
}
include(":softether-client")
project(":softether-client").projectDir = softEtherModuleDir
include(":app")
