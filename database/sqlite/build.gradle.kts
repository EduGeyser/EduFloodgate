val sqliteJdbcVersion = "3.53.4.0"

dependencies {
    provided(projects.core)
    implementation("org.xerial", "sqlite-jdbc", sqliteJdbcVersion)
}

description = "The Floodgate database extension for SQLite"
