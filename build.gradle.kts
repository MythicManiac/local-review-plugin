import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideLocalPath").get())
        bundledPlugin("Git4Idea")
        bundledModules("intellij.platform.vcs.dvcs", "intellij.platform.vcs.dvcs.impl", "intellij.platform.vcs.dvcs.impl.shared")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(25)
    // Kotlin interfaces of the platform (e.g. ToolWindowFactory) otherwise get bridge overrides that the verifier reports as
    // deprecated/experimental API overrides.
    compilerOptions { freeCompilerArgs.add("-jvm-default=no-compatibility") }
}

fun prop(name: String) = providers.gradleProperty(name)

changelog {
    version = prop("pluginVersion")
    repositoryUrl = prop("pluginUrl")
    groups = listOf("Added", "Changed", "Fixed")
}

intellijPlatform {
    projectName = "local-diff-review"
    buildSearchableOptions = false

    pluginConfiguration {
        name = prop("pluginName")
        // The listing text lives in docs/marketplace/ so it is reviewed like code and not duplicated in plugin.xml.
        description = providers.fileContents(layout.projectDirectory.file("docs/marketplace/description.html")).asText
            .zip(prop("pluginUrl").orElse("")) { html, url -> html.replace("{{pluginUrl}}", url.trimEnd('/')) }
        changeNotes = prop("pluginVersion").map { v ->
            with(changelog) {
                renderItem((getOrNull(v) ?: getUnreleased()).withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }
        vendor {
            name = prop("pluginVendorName").filter { it.isNotBlank() }
            email = prop("pluginVendorEmail").filter { it.isNotBlank() }
            url = prop("pluginVendorUrl").filter { it.isNotBlank() }
        }
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }

    // Credentials only ever come from the environment (see docs/PUBLISHING.md); nothing secret is stored in the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Use a custom channel (e.g. "eap") for pre-release builds: -PpublishChannel=eap
        channels = prop("publishChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    // Plugin Verifier: the installed IDE by default; -PverifyIdea=<version> adds IntelliJ IDEA, -PverifyRecommended all recommended IDEs.
    pluginVerification {
        ides {
            local(prop("ideLocalPath").get())
            // -PverifyIdea=2026.2.3 also verifies against IntelliJ IDEA (downloads ~1.6 GB).
            prop("verifyIdea").orNull?.let { create(IntelliJPlatformType.IntellijIdea, it) }
            if (prop("verifyRecommended").isPresent) recommended()
        }
    }
}

tasks.test {
    useJUnit()
}

// `./gradlew runIde -PdemoProject=<path>` opens that project in the sandbox IDE (dev smoke testing).
tasks.runIde {
    providers.gradleProperty("demoProject").orNull?.let { args = listOf(it) }
    jvmArgs("-Didea.trust.all.projects=true", "-Dide.show.tips.on.startup.default.value=false", "-Djb.privacy.policy.text=<!--999.999-->", "-Djb.consents.confirmation.enabled=false")
}

// ---- Marketplace readiness gate -------------------------------------------------------------------------------------
// Signing/publishing refuse to run while required listing metadata is missing or breaks Marketplace rules.
val marketplaceReadiness = tasks.register("marketplaceReadiness") {
    group = "publishing"
    description = "Checks plugin metadata against the JetBrains Marketplace approval rules."
    val name = prop("pluginName")
    val vendorName = prop("pluginVendorName").filter { it.isNotBlank() }
    val vendorEmail = prop("pluginVendorEmail").filter { it.isNotBlank() }
    val vendorUrl = prop("pluginVendorUrl").filter { it.isNotBlank() }
    val url = prop("pluginUrl")
    val version = prop("pluginVersion")
    val root = layout.projectDirectory
    doLast {
        val problems = mutableListOf<String>()
        val n = name.getOrElse("")
        if (n.isBlank() || n.length > 30) problems += "pluginName must be 1-30 characters."
        if (Regex("plugin|intellij|jetbrains|pycharm|webstorm|clion|rider", RegexOption.IGNORE_CASE).containsMatchIn(n)) {
            problems += "pluginName must not contain 'Plugin', 'IntelliJ', 'JetBrains' or a JetBrains product name."
        }
        if (vendorName.getOrElse("").isBlank()) problems += "pluginVendorName is not set (gradle.properties)."
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(vendorEmail.getOrElse(""))) problems += "pluginVendorEmail is not set to a valid address."
        if (!vendorUrl.getOrElse("").startsWith("http")) problems += "pluginVendorUrl must be an http(s) URL."
        if (!url.getOrElse("").startsWith("http")) problems += "pluginUrl (public source/homepage) must be an http(s) URL."
        val description = root.file("docs/marketplace/description.html").asFile
        if (!description.exists() || description.readText().length < 200) problems += "docs/marketplace/description.html is missing or too short."
        for (icon in listOf("pluginIcon.svg", "pluginIcon_dark.svg")) {
            if (!root.file("src/main/resources/META-INF/$icon").asFile.exists()) problems += "$icon is missing."
        }
        if (!root.file("LICENSE").asFile.exists()) problems += "LICENSE is missing."
        val log = root.file("CHANGELOG.md").asFile.readText()
        if (!Regex("^## \\[${Regex.escape(version.get())}]", RegexOption.MULTILINE).containsMatchIn(log)) {
            problems += "CHANGELOG.md has no '## [${version.get()}]' section (run patchChangelog)."
        }
        val shots = root.dir("docs/marketplace/screenshots").asFile.listFiles { f -> f.extension in setOf("png", "jpg") }.orEmpty()
        if (shots.size < 3) problems += "docs/marketplace/screenshots needs at least 3 screenshots (found ${shots.size})."
        if (problems.isNotEmpty()) {
            throw GradleException("Not ready for the Marketplace:\n" + problems.joinToString("\n") { " - $it" })
        }
        println("Marketplace readiness: OK")
    }
}
tasks.matching { it.name == "signPlugin" || it.name == "publishPlugin" }.configureEach { dependsOn(marketplaceReadiness) }
