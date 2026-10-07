pluginManagement {
    repositories { gradlePluginPortal(); maven("https://repo.papermc.io/repository/maven-public/") }
}
rootProject.name = "LogoutBodies"
include("api", "core", "platform-1_21_11")
include("platform-26_1_2", "platform-26_2")
// The local test fixture is excluded from the production source repository.
if (providers.gradleProperty("integrationProbe").orNull == "true") {
    include("integration-probe")
}
