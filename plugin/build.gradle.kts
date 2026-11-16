import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// From gradle.properties, or -Pversion=... (CI passes the tag's version).
val pluginVersion = providers.gradleProperty("version")

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
val hostOutput = layout.buildDirectory.dir("host")

val publishHost by tasks.registering(Exec::class) {
    inputs.files(fileTree(hostProject) { exclude("bin/**", "obj/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(hostOutput)
    // `dotnet publish -o` doesn't remove files from earlier builds, which would then be bundled too.
    val outputDir = hostOutput.get().asFile
    doFirst { outputDir.deleteRecursively() }
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

// HostSessionTest runs the published host.
tasks.test {
    dependsOn(publishHost)
    systemProperty("fsxcells.hostDll", hostOutput.get().file("FsxCells.Host.dll").asFile.absolutePath)
}

// The test IDE opens the samples folder.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    args(layout.projectDirectory.dir("../samples").asFile.absolutePath)
}

changelog {
    path = layout.projectDirectory.file("../CHANGELOG.md").asFile.absolutePath
    repositoryUrl = "https://github.com/akselkvitberg/fsx-cells"
    groups.empty()
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion

        // The Marketplace page's description: the part of README.md between the "Plugin description" comments.
        description = providers.fileContents(layout.projectDirectory.file("../README.md")).asText.map { readme ->
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            if (!readme.contains(start) || !readme.contains(end)) {
                throw GradleException("README.md must contain the $start ... $end section.")
            }
            markdownToHTML(readme.substringAfter(start).substringBefore(end).trim())
        }

        // The notes of this version in CHANGELOG.md, or the Unreleased ones for a version not in it (dev builds).
        val changelog = project.changelog
        changeNotes = pluginVersion.map { v ->
            with(changelog) {
                renderItem(
                    (getOrNull(v) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }

    // Optional: the Marketplace signs plugins itself. signPlugin is skipped without these.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 1.2.0 -> default channel; 1.2.0-eap.1 -> "eap" channel.
        channels = pluginVersion.map { v ->
            listOf(v.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // ./gradlew verifyPlugin: the JetBrains Plugin Verifier against the Rider releases this plugin supports.
    pluginVerification {
        ides {
            recommended()
        }
    }

    buildSearchableOptions = false
}
