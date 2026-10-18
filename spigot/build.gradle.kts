// Compile against the pre-record GameProfile API; SpigotVersionSpecificMethods reaches the
// record API of newer authlib versions through reflection
var authlibVersion = "1.5.21"
var guavaVersion = "33.7.2-jre"
var gsonVersion = "2.14.0"

dependencies {
    api(projects.core)

    implementation("org.incendo", "cloud-paper", Versions.cloudVersion)
    // hack to make pre 1.12 work
    implementation("com.google.guava", "guava", guavaVersion)

    compileOnlyApi("dev.folia", "folia-api", Versions.spigotVersion)
}

relocate("com.google.inject")
relocate("net.kyori")
relocate("org.incendo.cloud")
relocate("io.leangen.geantyref") // used in cloud
// hack to make pre 1.12 work
relocate("com.google.common")
relocate("com.google.guava")
// hack to make (old versions? of) Paper work
relocate("it.unimi")
// since 1.20
relocate("org.yaml")

// these dependencies are already present on the platform
provided("com.mojang", "authlib", authlibVersion)
provided("io.netty", "netty-transport", Versions.nettyVersion)
provided("io.netty", "netty-codec", Versions.nettyVersion)
provided("com.google.code.gson", "gson", gsonVersion)
