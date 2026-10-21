import com.quarkdown.buildlogic.KSP_COMMON_MAIN_TASK
import com.quarkdown.buildlogic.KSP_JVM_TASK
import com.quarkdown.buildlogic.hasSharedCommonMain
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.time.Year

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("quarkdown.multiplatform") apply false
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.github.ben-manes.versions") version "0.61.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.19"
    application
    `maven-publish`
}

val execOps: ExecOperations = serviceOf()

allprojects {
    group = "com.quarkdown"
    version = rootProject.file("version.txt").readText().trim()

    repositories {
        mavenCentral()
    }
}

// Subprojects that should not produce a published Maven artifact.
// `quarkdown-test` contains only integration tests and has no `src/main/`.
// Subprojects that must not produce a published Maven artifact.
//
// `quarkdown-test` and `quarkdown-core-test-fixtures` carry only test code.
//
// `quarkdown-cli` and `quarkdown-lsp` are the two AGPL v3 modules; everything
// else in the repository is GPL v3. This Maven repository exists so the viewer
// can link the GPL modules without taking on AGPL's network source-availability
// clause, so the AGPL artifacts are not published here at all. Nothing that is
// published depends on them. Consumers who want the CLI should take it from
// upstream's own distribution.
val publicationExcludedSubprojects =
    setOf(
        "quarkdown-test",
        "quarkdown-core-test-fixtures",
        "quarkdown-cli",
        "quarkdown-lsp",
    )

/**
 * Shared POM metadata applied to every Quarkdown Maven publication: project URL, licence,
 * developers, and SCM. Per-publication `name` and `description` are set at the call site.
 */
val publicationPomMetadata: MavenPom.() -> Unit = {
    url.set("https://github.com/iamgio/quarkdown")
    licenses {
        license {
            name.set("GNU General Public License v3.0")
            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
        }
    }
    developers {
        developer {
            id.set("iamgio")
            name.set("Giorgio Garofalo")
            url.set("https://github.com/iamgio")
        }
    }
    scm {
        connection.set("scm:git:https://github.com/iamgio/quarkdown.git")
        developerConnection.set("scm:git:ssh://git@github.com:iamgio/quarkdown.git")
        url.set("https://github.com/iamgio/quarkdown")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.github.ben-manes.versions")
    apply(plugin = "se.patrikerdes.use-latest-versions")
    apply(plugin = "maven-publish")

    plugins.withType<JavaPlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
    }

    // Bundle the applicable LICENSE into every Jar's META-INF directory so that
    // the license that applies to each artifact travels with it, as required by
    // GPL v3 §5(a) and AGPL v3 §5(a). Modules that ship their own LICENSE
    // (currently the AGPL-licensed `quarkdown-cli` and `quarkdown-lsp`) use that
    // file; everything else falls back to the root GPL v3 LICENSE.
    tasks.withType<Jar>().configureEach {
        val moduleLicense = projectDir.resolve("LICENSE")
        val licenseFile = if (moduleLicense.exists()) moduleLicense else rootProject.file("LICENSE")
        from(licenseFile) {
            into("META-INF")
        }
    }

    // Multiplatform modules publish binaries only. Their sources jars read the
    // directories KSP generates into, a wiring upstream never exercises because
    // it does not publish these modules at all, and Gradle rejects the
    // resulting implicit dependency. Nothing consuming this repository builds
    // against Quarkdown sources, so the jars are simply left out rather than
    // patched around. Plain JVM modules keep theirs, configured above.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            withSourcesJar(publish = false)
        }
    }

    afterEvaluate {
        if (project.name in publicationExcludedSubprojects) return@afterEvaluate

        // Since 2.6.4 the core modules are Kotlin Multiplatform with a single JVM
        // target. The multiplatform plugin registers its own publications (the
        // `kotlinMultiplatform` metadata module plus one per target) and does not
        // apply the `java` plugin, so a `components["java"]` publication is both
        // unavailable and unnecessary there. Plain JVM modules still need one.
        val isMultiplatform = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        val isJvmOnly = !isMultiplatform && plugins.hasPlugin("java")
        if (!isMultiplatform && !isJvmOnly) return@afterEvaluate

        // GitHub Packages publication.
        //
        // The repository URL is derived from the `GITHUB_REPOSITORY` environment variable set by
        // GitHub Actions, so the same configuration publishes to the correct registry for the
        // upstream repo and for any fork. Credentials are read from `GITHUB_ACTOR` and
        // `GITHUB_TOKEN` in CI, and fall back to the `gpr.user` / `gpr.key` Gradle properties for
        // local publication.
        //
        // `GhPages` is a file-system repository whose output is synced to the fork's
        // `gh-pages` branch by `.github/workflows/publish-fork.yml`. GitHub Pages then
        // serves it as a public, anonymous Maven repo at
        // `https://jortegac.github.io/quarkdown/`.
        extensions.configure<PublishingExtension> {
            if (isJvmOnly) {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
            }
            // Applies to the publications created above and to the ones the
            // multiplatform plugin contributes on its own.
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(artifactId)
                    description.set("Quarkdown module: $artifactId")
                    publicationPomMetadata()
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    val repository = System.getenv("GITHUB_REPOSITORY") ?: "iamgio/quarkdown"
                    url = uri("https://maven.pkg.github.com/$repository")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                            ?: providers.gradleProperty("gpr.user").orNull
                        password = System.getenv("GITHUB_TOKEN")
                            ?: providers.gradleProperty("gpr.key").orNull
                    }
                }
                maven {
                    name = "GhPages"
                    url = uri(rootProject.layout.buildDirectory.dir("gh-pages-maven-repo"))
                }
            }
        }
    }
}

// Fat JAR / Distribution dependencies
gradle.projectsEvaluated {
    dependencies {
        subprojects.forEach {
            when {
                it.extra.has("noRuntime") && it.extra["noRuntime"] == true -> {
                    compileOnly(project(it.path))
                }

                else -> {
                    implementation(project(it.path))
                }
            }
        }
    }
}

application {
    mainClass.set("com.quarkdown.cli.QuarkdownCliKt")
}

ktlint {
    version.set("1.7.1")
}

// Dokka

dokka {
    dokkaPublications.html {
        outputDirectory.set(
            layout.buildDirectory
                .file("docs")
                .get()
                .asFile,
        )
    }
}

/**
 * Whether [project] uses the Quarkdoc plugin, which means its documentation must be included in the distribution zip.
 */
fun usesQuarkdoc(project: Project): Boolean {
    val quarkdoc = project(":quarkdown-quarkdoc")
    return project.configurations
        .findByName("dokkaPlugin")
        ?.dependencies
        ?.filterIsInstance<ProjectDependency>()
        ?.any { it.path == quarkdoc.path } == true
}

val quarkdocGenerate: TaskProvider<Task> =
    tasks.register("quarkdocGenerate") {
        group = "documentation"
        description = "Generates the Quarkdoc documentation for modules that include the Quarkdoc plugin."

        dependencies {
            subprojects.filter(::usesQuarkdoc).forEach {
                dokka(project(it.path))
            }
        }

        dependsOn(tasks.dokkaGenerate)
    }

tasks.register("quarkdocGenerateAll") {
    group = "documentation"
    description = "Generates the Quarkdoc documentation for all modules."

    dependencies {
        subprojects.forEach {
            dokka(project(it.path))
        }
    }

    dependsOn(tasks.dokkaGenerate)
}

allprojects {
    fun asset(path: String): File = project(":quarkdown-quarkdoc").projectDir.resolve("src/main/resources/$path")

    dokka {
        pluginsConfiguration.html {
            val year = Year.now().value
            footerMessage.set("&copy; $year Quarkdown")
            customAssets.from(*asset("assets/images").listFiles()!!)
            customStyleSheets.from(asset("styles/stylesheet.css"))
        }
    }

    // KSP-generated source files must be included in the Dokka source set.
    afterEvaluate {
        if (usesQuarkdoc(this) && pluginManager.hasPlugin("com.google.devtools.ksp")) {
            val multiplatform = pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
            val documentedSourceSet = if (multiplatform) "commonMain" else "main"
            val generatedSources =
                when {
                    !multiplatform -> "generated/ksp/main/kotlin"
                    hasSharedCommonMain -> "generated/ksp/metadata/commonMain/kotlin"
                    else -> "generated/ksp/jvm/jvmMain/kotlin"
                }
            val kspTask =
                when {
                    !multiplatform -> "kspKotlin"
                    hasSharedCommonMain -> KSP_COMMON_MAIN_TASK
                    else -> KSP_JVM_TASK
                }

            dokka {
                dokkaSourceSets.named(documentedSourceSet) {
                    sourceRoots.from(layout.buildDirectory.dir(generatedSources))
                    suppressGeneratedFiles.set(false)
                }
                if (multiplatform) {
                    dokkaSourceSets.matching { it.name != documentedSourceSet }.configureEach {
                        suppress.set(true)
                    }
                }
            }
            listOf("dokkaGeneratePublicationHtml", "dokkaGenerateModuleHtml").forEach { taskName ->
                tasks.named(taskName) { dependsOn(kspTask) }
            }
        }
    }
}

// Tasks

/**
 * Populates a [CopySpec] with the Quarkdown install library layout, with subdirectories such as `qd/` and `html/`.
 * Used by both [distributions]`.main` and [assembleDevLib].
 *
 * This layout is navigable at runtime via the `install-layout-navigator` module.
 */
val installLibLayout: CopySpec.() -> Unit = {
    // .qd library files.
    into("qd") {
        from(project(":quarkdown-libs").file("src/main/resources")) {
            include("*.qd")
        }
    }
    // HTML rendering resources (third-party libraries, themes, scripts) for offline rendering.
    into("html") {
        from(project(":quarkdown-html").layout.buildDirectory.dir("install"))
    }
    // Agent skills.
    into("skills") {
        from(rootProject.file("skills"))
    }
}

/**
 * The tasks producing a subproject's library JAR: `jar` for Kotlin/JVM projects, `jvmJar` for multiplatform ones.
 */
val Project.libraryJarTasks: TaskCollection<Task>
    get() = tasks.matching { it.name == "jar" || it.name == "jvmJar" }

// Bundled JVM runtime

/**
 * A target platform for which a bundled minimal JRE is built via `jlink` and a distribution zip is produced.
 * Cross-compilation works because `jlink` only reads platform-specific bytes from the target JDK's `jmods/`,
 * so any host `jlink` (e.g., the CI Linux runner's) can emit a Windows or macOS runtime by pointing
 * `--module-path` at the target JDK's `jmods/` directory.
 */
data class JlinkTarget(
    /** Used in zip file names, e.g. `quarkdown-linux-x64.zip`. */
    val id: String,
    /** Used as suffix for per-target task names, e.g. `bundleRuntimeLinuxX64`. */
    val taskSuffix: String,
    /** Filename of the Temurin JDK archive on Adoptium's GitHub releases. */
    val jdkArchive: String,
    /** Path within the extracted archive to JAVA_HOME (its `jmods/` parent). */
    val jdkHomeRelative: String,
)

val jdkVersion = providers.gradleProperty("bundledJdkVersion").get()!!
val jdkVersionEncoded = jdkVersion.replace("+", "%2B") // URL-encoded for the GitHub release path
val jdkBaseUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-$jdkVersionEncoded/"
val jdkFileVersion = jdkVersion.replace("+", "_") // Adoptium uses underscores in archive filenames

val jlinkTargets =
    listOf(
        JlinkTarget(
            id = "linux-x64",
            taskSuffix = "LinuxX64",
            jdkArchive = "OpenJDK17U-jdk_x64_linux_hotspot_$jdkFileVersion.tar.gz",
            jdkHomeRelative = "jdk-$jdkVersion",
        ),
        JlinkTarget(
            id = "macos-x64",
            taskSuffix = "MacosX64",
            jdkArchive = "OpenJDK17U-jdk_x64_mac_hotspot_$jdkFileVersion.tar.gz",
            jdkHomeRelative = "jdk-$jdkVersion/Contents/Home",
        ),
        JlinkTarget(
            id = "macos-aarch64",
            taskSuffix = "MacosAarch64",
            jdkArchive = "OpenJDK17U-jdk_aarch64_mac_hotspot_$jdkFileVersion.tar.gz",
            jdkHomeRelative = "jdk-$jdkVersion/Contents/Home",
        ),
        JlinkTarget(
            id = "windows-x64",
            taskSuffix = "WindowsX64",
            jdkArchive = "OpenJDK17U-jdk_x64_windows_hotspot_$jdkFileVersion.zip",
            jdkHomeRelative = "jdk-$jdkVersion",
        ),
    )

// SPI-loaded modules that jdeps cannot detect via static analysis:
// - `jdk.crypto.ec`: TLS handshakes with EC certificates (most modern HTTPS endpoints).
val jlinkSpiModules = listOf("jdk.crypto.ec")

/**
 * Resolves the full set of JDK modules required by Quarkdown's runtime classpath,
 * combining `jdeps` static analysis with explicitly listed SPI-loaded modules.
 * Module names are platform-agnostic, so the host's `jdeps` can be used regardless of target.
 */
fun resolveRequiredModules(): String {
    val jdepsOutput =
        ByteArrayOutputStream()
            .also { out ->
                execOps.exec {
                    val jars = configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
                    commandLine(
                        "jdeps",
                        "--ignore-missing-deps",
                        "--multi-release",
                        "17",
                        "--print-module-deps",
                        *jars.map { it.absolutePath }.toTypedArray(),
                    )
                    standardOutput = out
                }
            }.toString()
            .trim()

    // jdeps may output warnings before the final line; the module list is always the last line.
    val detectedModules = jdepsOutput.lines().last().trim()
    return (detectedModules.split(",") + jlinkSpiModules).joinToString(",")
}

/**
 * Host-platform bundled JRE, used by `installDist` for development and host-only runs.
 * Cross-platform release zips use the per-target `bundleRuntime<Target>` tasks instead.
 */
val bundleRuntime =
    tasks.register("bundleRuntime") {
        group = "distribution"
        description = "Creates a minimal JRE via jlink for the host platform (used by installDist)."

        dependsOn(tasks.jar, subprojects.map { it.libraryJarTasks })

        val runtimeDir = layout.buildDirectory.dir("runtime")
        outputs.dir(runtimeDir)

        doLast {
            val outputDir = runtimeDir.get().asFile
            delete(outputDir)
            execOps.exec {
                commandLine(
                    "jlink",
                    "--add-modules",
                    resolveRequiredModules(),
                    "--strip-debug",
                    "--no-man-pages",
                    "--no-header-files",
                    "--compress",
                    "2",
                    "--output",
                    outputDir.absolutePath,
                )
            }

            // jlink emits read-only legal/ files that cause overwrite errors.
            outputDir
                .walk()
                .filter { it.isFile && !it.canWrite() }
                .forEach { it.setWritable(true) }
        }
    }

// Per-target tasks: download the JDK, build a target-specific JRE via jlink,
// and assemble a distribution zip with that runtime bundled in.
jlinkTargets.forEach { target ->
    val jdkRoot = layout.buildDirectory.dir("jdks/${target.id}")
    val runtimeDir = layout.buildDirectory.dir("runtimes/${target.id}")

    val downloadJdkTask =
        tasks.register("downloadJdk${target.taskSuffix}") {
            group = "distribution"
            description = "Downloads and extracts the Temurin JDK for ${target.id} (used for jlink cross-compilation)."
            outputs.dir(jdkRoot)

            doLast {
                val rootDir = jdkRoot.get().asFile
                val jdkHomeDir = File(rootDir, target.jdkHomeRelative)
                if (jdkHomeDir.resolve("jmods").isDirectory) return@doLast

                delete(rootDir)
                rootDir.mkdirs()
                val archive = File(rootDir, target.jdkArchive)
                ant.invokeMethod("get", mapOf("src" to "$jdkBaseUrl${target.jdkArchive}", "dest" to archive))

                when {
                    archive.name.endsWith(".tar.gz") -> {
                        execOps.exec {
                            commandLine("tar", "-xzf", archive.absolutePath, "-C", rootDir.absolutePath)
                        }
                    }

                    archive.name.endsWith(".zip") -> {
                        copy {
                            from(zipTree(archive))
                            into(rootDir)
                        }
                    }
                }
                archive.delete()
            }
        }

    val bundleRuntimeTargetTask =
        tasks.register("bundleRuntime${target.taskSuffix}") {
            group = "distribution"
            description = "Creates a minimal JRE via jlink for ${target.id}, using the target platform's jmods."
            dependsOn(downloadJdkTask, tasks.jar, subprojects.map { it.libraryJarTasks })
            inputs.dir(jdkRoot)
            outputs.dir(runtimeDir)

            doLast {
                val jmodsDir =
                    jdkRoot
                        .get()
                        .asFile
                        .resolve(target.jdkHomeRelative)
                        .resolve("jmods")
                val outputDir = runtimeDir.get().asFile
                delete(outputDir)

                execOps.exec {
                    commandLine(
                        "jlink",
                        "--module-path",
                        jmodsDir.absolutePath,
                        "--add-modules",
                        resolveRequiredModules(),
                        "--strip-debug",
                        "--no-man-pages",
                        "--no-header-files",
                        "--compress",
                        "2",
                        "--output",
                        outputDir.absolutePath,
                    )
                }
            }
        }

    tasks.register("dist${target.taskSuffix}Zip", Zip::class) {
        group = "distribution"
        description = "Builds the Quarkdown distribution zip for ${target.id}, with a bundled jlink runtime."
        archiveBaseName.set("quarkdown-${target.id}")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))

        // Wrap everything in a top-level `quarkdown/` directory, mirroring the legacy zip layout.
        into("quarkdown") {
            // bin/, lib/, docs/ from installDist (excluding its host-targeted runtime/).
            from(tasks.installDist.map { it.destinationDir }) {
                exclude("runtime/**")
            }
            // Per-target runtime.
            from(runtimeDir) {
                into("runtime")
            }
        }

        dependsOn(tasks.installDist, bundleRuntimeTargetTask)
    }
}

// Aggregate task that builds distribution zips for every target platform.
tasks.register("distZipAll") {
    group = "distribution"
    description = "Builds Quarkdown distribution zips for all target platforms."
    dependsOn(jlinkTargets.map { "dist${it.taskSuffix}Zip" })
}

distributions.main {
    contents {
        into("lib", installLibLayout)
        // Bundled minimal JRE for the host platform, created by bundleRuntime.
        // Per-platform zips use the per-target bundleRuntime<Target> tasks instead.
        from(layout.buildDirectory.dir("runtime")) {
            into("runtime")
        }
        // Include the generated Dokka documentation, generated by quarkdocGenerate.
        val dokkaOutputDir = layout.buildDirectory.file("docs")
        from(dokkaOutputDir) {
            into("docs")
        }
        // .qd wiki sources for the agent skill.
        into("docs/wiki") {
            from(rootProject.file("docs")) {
                include("**/*.qd")
            }
            includeEmptyDirs = false
        }
        // Browser installation scripts, invoked by package managers and installers.
        into("scripts") {
            from(project(":quarkdown-html-pdf").file("scripts")) {
                include("*.sh", "*.ps1")
                filePermissions { unix("rwxr-xr-x") }
            }
            from(project(":quarkdown-html-pdf").file("scripts")) {
                include("chrome.properties")
            }
        }
    }
}

// Assembles a dev-time install `lib/` layout at `<rootProject>/build/dev-lib`, mirroring
// the distribution layout so that `gradle run` and IntelliJ runs can resolve the install
// directory at runtime.
val assembleDevLib =
    tasks.register<Sync>("assembleDevLib") {
        dependsOn(":quarkdown-html:bundleThirdParty")
        into(layout.buildDirectory.dir("dev-lib"))
        installLibLayout()
    }

/**
 * Standalone zip artifact containing the Quarkdown install `lib/` layout, with the same
 * `lib/qd`, `lib/html`, `lib/skills` shape produced by [installLibLayout]. Published as a
 * Maven artifact alongside the JVM modules so that consumers embedding Quarkdown can fetch
 * the runtime resources (`.qd` libraries, HTML themes and scripts, third-party bundles,
 * agent skills) without having to vendor the upstream source or unpack the full
 * distribution zip.
 */
val installLibZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the Quarkdown install `lib/` layout (qd, html, skills) as a standalone zip artifact."

    archiveBaseName.set("quarkdown-install-lib")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // The HTML install layout is produced as a side-effect of these bundling tasks.
    // Provider-based wiring should detect these automatically, but declaring them explicitly
    // keeps the published artifact reliably reproducible.
    dependsOn(
        ":quarkdown-html:assembleThemes",
        ":quarkdown-html:bundleTypeScript",
        ":quarkdown-html:bundleHighlightJs",
        ":quarkdown-html:bundleThirdParty",
    )

    into("lib", installLibLayout)
}

// Root-project publication of the standalone install-lib zip.
//
// The JVM module publications live on the subprojects (see the `subprojects` block above);
// this block adds a single artifact on the root so consumers can resolve the runtime
// install layout under a stable Maven coordinate:
//
//     com.quarkdown:quarkdown-install-lib:<version>@zip
publishing {
    publications {
        create<MavenPublication>("installLib") {
            artifactId = "quarkdown-install-lib"
            artifact(installLibZip) {
                extension = "zip"
            }
            pom {
                name.set("quarkdown-install-lib")
                description.set("Quarkdown install `lib/` layout (qd, html, skills) as a standalone zip artifact.")
                publicationPomMetadata()
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            val repository = System.getenv("GITHUB_REPOSITORY") ?: "iamgio/quarkdown"
            url = uri("https://maven.pkg.github.com/$repository")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
        // File-system repository mirroring the subproject `GhPages` setup above,
        // so the install-lib zip lands in the same Maven layout consumed from
        // `https://jortegac.github.io/quarkdown/`.
        maven {
            name = "GhPages"
            url = uri(rootProject.layout.buildDirectory.dir("gh-pages-maven-repo"))
        }
    }
}

tasks.installDist {
    dependsOn(quarkdocGenerate, bundleRuntime)
}

tasks.distZip {
    dependsOn(quarkdocGenerate, bundleRuntime)
    archiveVersion.set("")
}

tasks.distTar {
    dependsOn(quarkdocGenerate, bundleRuntime)
    archiveVersion.set("")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.ktlintCheck)
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = files("lib/*") // Fixes the 'Input line is too long' error on Windows.
    // Prepends subscripts to the generated start scripts.
    doLast {
        val dir = file("scripts")
        val scripts = sequenceOf("bootstrap")

        scripts.forEach { scriptName ->
            val unixPrefix = dir.resolve("$scriptName.sh").readText() + "\n"
            val windowsPrefix = dir.resolve("$scriptName.bat").readText() + "\n"
            unixScript.writeText("#!/bin/sh\n\n" + unixPrefix + unixScript.readText())
            windowsScript.writeText("@echo off\n\n" + windowsPrefix + windowsScript.readText())
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.ALL
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register("printVersion") {
    doLast {
        println(project.version)
    }
}

// Dependency updates

allprojects {
    tasks.dependencyUpdates {
        rejectVersionIf {
            Regex("[.-](alpha|beta|rc|cr|m|preview|b|ea)", RegexOption.IGNORE_CASE) in candidate.version
        }
    }

    tasks.useLatestVersions {
        updateBlacklist =
            listOf(
                "org.jetbrains.kotlin",
                "org.jetbrains.dokka",
            )
    }
}
