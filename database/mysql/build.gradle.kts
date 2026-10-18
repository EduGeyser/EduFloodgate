dependencies {
  provided(projects.core)

  implementation("com.zaxxer", "HikariCP", "7.1.0")

  implementation("com.mysql", "mysql-connector-j", "26.7.0") {
    exclude("com.google.protobuf", "protobuf-java")
  }
}

description = "The Floodgate database extension for MySQL"

// relocate everything from mysql-connector-j and HikariCP
relocate("com.mysql")
relocate("com.zaxxer.hikari")
relocate("org.slf4j")
