import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("floodgate.base-conventions")
    id("com.gradleup.shadow")
}

tasks {
    named<Jar>("jar") {
        archiveClassifier.set("unshaded")
        from(project.rootProject.file("LICENSE"))
    }
    val shadowJar = named<ShadowJar>("shadowJar") {
        // only the shipped platform jars carry the edufloodgate name. Everything else keeps
        // the upstream name: the database jar loader validates upstream's file name format
        val platformJars = setOf("bungee", "spigot", "velocity")
        val jarPrefix = if (project.name in platformJars) "edufloodgate" else "floodgate"
        archiveBaseName.set("$jarPrefix-${project.name}")
        archiveVersion.set("")
        archiveClassifier.set("")

        val sJar: ShadowJar = this

        // Shadow resolves the bundled dependencies as a task input, before doFirst runs, so
        // the provided dependencies are excluded through a spec that reads them lazily.
        // An empty part of a provided group:name:version matches anything
        val projectName = project.name
        dependencies {
            exclude { resolved ->
                val coordinates = listOf(resolved.moduleGroup, resolved.moduleName, resolved.moduleVersion)
                providedDependencies[projectName].orEmpty().any { (name, _) ->
                    name.split(":").withIndex().all { (i, part) -> part.isEmpty() || part == coordinates[i] }
                }
            }
        }

        doFirst {
            providedDependencies[project.name]?.forEach { (name, _) ->
                println("Excluding $name from ${project.name}")
            }

            // relocations made in included project dependencies are for whatever reason not
            // forwarded to the project implementing the dependency.
            // (e.g. a relocation in `core` will relocate for core. But when you include `core` in
            // for example Velocity, the relocation will be gone for Velocity)
            addRelocations(project, sJar)
        }

        val destinationDir = System.getenv("DESTINATION_DIRECTORY");
        if (destinationDir != null) {
            destinationDirectory.set(file(destinationDir))
        }
    }
    named("build") {
        dependsOn(shadowJar)
    }
}

fun addRelocations(project: Project, shadowJar: ShadowJar) {
    callAddRelocations(project.configurations.api.get(), shadowJar)
    callAddRelocations(project.configurations.implementation.get(), shadowJar)

    relocatedPackages[project.name]?.forEach { pattern ->
        println("Relocating $pattern for ${shadowJar.project.name}")
        shadowJar.relocate(pattern, "org.geysermc.floodgate.shadow.$pattern")
    }
}

fun callAddRelocations(configuration: Configuration, shadowJar: ShadowJar) =
    configuration.dependencies.forEach {
        if (it is ProjectDependency)
            addRelocations(project.project(it.path), shadowJar)
    }