plugins { id("io.papermc.paperweight.userdev") }
dependencies {
    paperweight.paperDevBundle("26.1.2.build.74-stable")
    implementation(project(":core"))
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
