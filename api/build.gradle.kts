plugins { `java-library`; `maven-publish` }
dependencies { compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91") }

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "logoutbodies-api"
            pom {
                name.set("LogoutBodies API")
                description.set("Public Bukkit API for LogoutBodies")
                licenses { license { name.set("GNU General Public License v3.0"); url.set("https://www.gnu.org/licenses/gpl-3.0.html") } }
            }
        }
    }
}
