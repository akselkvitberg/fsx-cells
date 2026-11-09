plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Rider frontend. Bump this to match the Rider version you use day to day.
        rider(providers.gradleProperty("riderVersion").get())
        // The embedded browser for HTML output. Optional at runtime (see plugin.xml).
        bundledPlugin("com.intellij.modules.jcef")
    }
}

// Publish the F# host and bundle it into the plugin under <plugin>/host.
val hostProject = layout.projectDirectory.dir("../host/FsxCells.Host")
val fsiSettingsProject = layout.projectDirectory.dir("../host/FsxCells.FsiSettings")
val hostOutput = layout.buildDirectory.dir("host")

val publishHost by tasks.registering(Exec::class) {
    inputs.files(fileTree(hostProject) { exclude("bin/**", "obj/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(fsiSettingsProject) { exclude("bin/**", "obj/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(hostOutput)
    commandLine(
        "dotnet", "publish", hostProject.asFile.absolutePath,
        "-c", "Release", "-o", hostOutput.get().asFile.absolutePath, "--nologo",
    )
}

// runIde and tests use their own sandbox tasks (prepareSandbox_runIde, ...), so configure all of them.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(publishHost) {
        into(pluginName.map { "$it/host" })
    }
}

// The test IDE opens the samples folder.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    args(layout.projectDirectory.dir("../samples").asFile.absolutePath)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}
