plugins {
    base
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24" apply false
}
allprojects {
    group = "io.github.logoutbodies"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
}
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    if (name.startsWith("platform-")) {
        val minecraft = name.removePrefix("platform-").replace('_', '.')
        extensions.configure<SourceSetContainer> {
            named("main") {
                java.srcDir(rootProject.file("platform-common/src/main/java"))
                resources.srcDir(rootProject.file("platform-common/src/main/resources"))
            }
        }
        tasks.withType<ProcessResources>().configureEach {
            filesMatching("plugin.yml") { expand("version" to project.version, "minecraft" to minecraft) }
        }
        val bundled = configurations.create("bundled")
        dependencies { add("bundled", "org.xerial:sqlite-jdbc:3.50.3.0") { isTransitive = false } }
        tasks.named<Jar>("jar") {
            archiveBaseName.set("LogoutBodies-$minecraft")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            manifest.attributes("paperweight-mappings-namespace" to "mojang")
            from(rootProject.file("LICENSE")) { into("META-INF"); rename { "LICENSE.LogoutBodies" } }
            from(project(":api").tasks.named("jar").map { zipTree((it as Jar).archiveFile) })
            from(project(":core").tasks.named("jar").map { zipTree((it as Jar).archiveFile) })
            from(bundled.map { zipTree(it) })
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
        }
    }
}
