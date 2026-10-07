plugins { id("io.papermc.paperweight.userdev") }
dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-20260511.115010-91")
    implementation(project(":core"))
}
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
