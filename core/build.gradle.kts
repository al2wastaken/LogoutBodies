plugins { `java-library` }
dependencies {
    api(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
}
