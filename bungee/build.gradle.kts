var bungeeProxyVersion = "1.21-R0.4-SNAPSHOT"
var bungeeApiVersion = "26.1-R0.1-SNAPSHOT"
var gsonVersion = "2.14.0"
var guavaVersion = "33.7.2-jre"

dependencies {
    api(projects.core)
    implementation("org.incendo", "cloud-bungee", Versions.cloudVersion)
}

relocate("com.google.inject")
relocate("net.kyori")
relocate("org.incendo.cloud")
// used in cloud
relocate("io.leangen.geantyref")
// since 1.20
relocate("org.yaml")

// these dependencies are already present on the platform
provided("net.md-5", "bungeecord-proxy", bungeeProxyVersion, includeTransitiveDeps = false)
provided("net.md-5", "bungeecord-api", bungeeApiVersion)
provided("com.google.code.gson", "gson", gsonVersion)
provided("com.google.guava", "guava", guavaVersion)
