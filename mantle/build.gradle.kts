plugins {
    base
    id("io.ia.sdk.modl") version("0.5.0")
}

val sdkVersion by extra("8.3.9")

// The middle digit has to match the platform's minor version or the gateway faults the module, so this is
// not semver and never can be: 8.3 means x.3.y. Inductive's own modules follow it (Historian 1.3.9, OPC-UA
// 10.3.9, Perspective 3.3.9 against 8.3.9). The first digit is ours; the last is our patch number.
//
// A release overrides it from the tag: -PmantleVersion=1.3.2, which .github/workflows/release.yml passes.
val mantleVersion: String = (findProperty("mantleVersion") as String?) ?: "1.3.0"

allprojects {
    version = mantleVersion
    group = "com.joyautomation.mantle"
}

ignitionModule {
    // The name is "Mantle", never "Mantle for Ignition": Inductive's Showcase rules forbid "Ignition" inside a
    // module's name and allow "for Ignition" only as trailing prose. See ../docs/releasing.md.
    fileName.set("Mantle")
    name.set("Mantle")
    id.set("com.joyautomation.mantle")
    moduleVersion.set("${project.version}")
    moduleDescription.set(
        "Sparkplug B host application. Tags are created as they are born, historized by default, " +
            "and customized in place."
    )
    // The gateway shows this when the module is installed, and ACCEPT_MODULE_LICENSES exists because modules
    // are expected to have one. It carries the Apache-2.0 grant and the third-party notices (Tahu is EPL-2.0,
    // whose licence and source pointer have to travel with the binary).
    license.set("license.html")

    // User documentation, shown on the module's page in the gateway. Shipping it inside the .modl means the
    // documentation matches the build that is installed, rather than whatever a website says today.
    documentationFiles.from(fileTree("doc"))
    documentationIndex.set("index.html")

    requiredIgnitionVersion.set("8.3.0")
    requiredFrameworkVersion.set("8")
    // no licensing: without this the gateway runs the module on the two hour trial timer
    freeModule.set(true)

    // Not required, but when the historian is present it has to be up before our tags start producing values,
    // or the first values after a gateway start are dropped.
    moduleDependencySpecs {
        register("com.inductiveautomation.historian") {
            scope = "G"
            required = false
        }
    }

    projectScopes.putAll(
        mapOf(
            ":gateway" to "G",
            ":web-ui" to "G"
        )
    )

    hooks.putAll(
        mapOf(
            "com.joyautomation.ignition.mantle.MantleGatewayHook" to "G"
        )
    )

    // Signed when a keystore is configured (ignition.signing.* in ~/.gradle/gradle.properties, or the signModule
    // flags: see ../docs/releasing.md), unsigned otherwise. An unsigned module only loads on a gateway started
    // with -Dignition.allowunsignedmodules=true, which is what the dev stack and CI do.
    skipModlSigning.set(
        !project.hasProperty("ignition.signing.keystoreFile") && !project.hasProperty("ignition.signing.pkcs11CfgFile")
    )
}

// The module manifest carries a vendor, and the gateway shows it on the module's page, but the Gradle plugin
// has no setting for it (checked against io.ia.sdk.modl 0.5.0). Its parser reads <vendorname>, <vendorid> and
// <vendorcontactinfo>, so they go in after the plugin writes the file and before it is packed — which leaves
// signing, which runs later still, working on the finished manifest.
val vendorName = "Joy Automation"
val vendorUrl = "https://joyautomation.com"

tasks.named("writeModuleXml") {
    doLast {
        val moduleXml = layout.buildDirectory.file("moduleContent/module.xml").get().asFile
        val xml = moduleXml.readText()
        if (!xml.contains("<vendorname>")) {
            moduleXml.writeText(
                xml.replaceFirst(
                    "</description>",
                    "</description>\n\t\t<vendorname>$vendorName</vendorname>" +
                        "\n\t\t<vendorcontactinfo>$vendorUrl</vendorcontactinfo>",
                )
            )
        }
    }
}

// ── distribution checks ──────────────────────────────────────────────────────
//
// Run in CI on every change, because the failure they catch is the kind nobody notices until it is a legal
// problem or a support call: a transitive dependency arriving under a copyleft licence, or the .modl going
// out without the things the Showcase requires it to have.


/**
 * The licences declared for one artifact, following <parent> when the POM does not declare its own — which
 * Netty, protobuf and the Apache Commons projects all do. Without the parent walk this reported UNKNOWN for
 * more than a third of the tree, which is worse than no audit at all: it looks like diligence.
 *
 * Returns null only when nothing in the chain declares a licence.
 */
fun Project.licensesOf(coordinates: String, depth: Int = 0): String? {
    if (depth > 5) return null
    val pom = runCatching {
        configurations.detachedConfiguration(dependencies.create("$coordinates@pom")).resolve().first().readText()
    }.getOrNull() ?: return null

    // <licenses> at the top level only — a <dependency> block can carry one too, and it is not ours.
    val declared = Regex("<licenses>(.*?)</licenses>", RegexOption.DOT_MATCHES_ALL).find(pom)
        ?.let { Regex("<name>(.*?)</name>").findAll(it.groupValues[1]).map { m -> m.groupValues[1].trim() } }
        ?.joinToString("; ")
        ?.takeIf { it.isNotBlank() }
    if (declared != null) return declared

    val parent = Regex("<parent>(.*?)</parent>", RegexOption.DOT_MATCHES_ALL).find(pom)?.groupValues?.get(1)
        ?: return null
    fun field(name: String) = Regex("<$name>(.*?)</$name>").find(parent)?.groupValues?.get(1)?.trim()
    val group = field("groupId") ?: return null
    val artifact = field("artifactId") ?: return null
    val version = field("version") ?: return null
    return licensesOf("$group:$artifact:$version", depth + 1)
}

/**
 * Every jar bundled into the .modl, with the licence from its own POM. Fails on the GPL family, which cannot
 * be shipped inside a signed, closed-format module without infecting it. Eclipse Tahu's EPL-2.0 is weak
 * copyleft and allowed: it is shipped unmodified, and its licence and source pointer travel in NOTICE.
 */
val checkDependencyLicenses by tasks.registering {
    group = "verification"
    description = "Fails if anything bundled in the .modl is GPL/LGPL/AGPL."

    val runtime = project(":gateway").configurations.named("runtimeClasspath")
    val reportFile = layout.buildDirectory.file("reports/dependency-licenses.txt")
    outputs.file(reportFile)

    doLast {
        val forbidden = Regex("(?i)\\b(GNU General Public|GPL-[23]|LGPL|AGPL|GPLv[23])\\b")
        // Not licences, but names that match the pattern above and are not what it is looking for.
        val allowedExceptions = setOf("GPL with Classpath Exception", "Classpath-exception")

        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val artifactNames = mutableListOf<String>()

        runtime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group != project.group.toString() } // our own subprojects; this repo's LICENSE covers them
            .distinctBy { "${it.group}:${it.name}" }
            .sortedBy { "${it.group}:${it.name}" }
            .forEach { id ->
                val coordinates = "${id.group}:${id.name}:${id.version}"
                val licenses = licensesOf(coordinates) ?: "UNKNOWN — check by hand"

                lines += "%-62s %s".format(coordinates, licenses)
                artifactNames += id.name
                if (forbidden.containsMatchIn(licenses) && allowedExceptions.none { licenses.contains(it) }) {
                    problems += "$coordinates is $licenses"
                }
            }

        // NOTICE has to name everything that ships, or it is not doing its job. Checked by artifact name
        // rather than by hand, because "add the dependency" and "update NOTICE" are never the same commit.
        val notice = rootDir.resolveSibling("NOTICE").takeIf { it.exists() } ?: rootDir.parentFile.resolve("NOTICE")
        if (notice.exists()) {
            val text = notice.readText()
            val undeclared = artifactNames.filterNot { text.contains(it) }
            if (undeclared.isNotEmpty()) {
                problems += undeclared.map { "$it ships in the .modl but is not named in NOTICE" }
            }
        } else {
            problems += "no NOTICE file found; the EPL-2.0 dependency requires one to travel with the binary"
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("${lines.size} bundled dependencies; licences listed in ${report.relativeTo(rootDir)}")

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The .modl is not fit to distribute:\n  " + problems.joinToString("\n  ")
            )
        }
    }
}

/**
 * The things the Ignition Module Showcase and the gateway both expect to find in a finished .modl. Checked
 * against the built artifact rather than the build script, so it catches the plugin silently dropping
 * something as much as a bad setting.
 */
val checkModuleArtifact by tasks.registering {
    group = "verification"
    description = "Checks the built .modl carries its manifest fields, licence and documentation."
    dependsOn(tasks.named("zipModule"))
    // Always re-run: the whole point is to look at the artifact on disk, which signModule replaces.
    outputs.upToDateWhen { false }

    doLast {
        // signModule leaves Mantle.unsigned.modl beside the signed Mantle.modl. Check the signed one when
        // there is one: it is the artifact that gets distributed, and it is not byte-identical to the other.
        val built = layout.buildDirectory.get().asFile.listFiles { f -> f.name.endsWith(".modl") }.orEmpty()
        val modl = built.firstOrNull { !it.name.endsWith("unsigned.modl") }
            ?: built.firstOrNull()
            ?: throw GradleException("no .modl was built")
        val signed = java.util.zip.ZipFile(modl).use { zip ->
            zip.getEntry("signatures.properties") != null && zip.getEntry("certificates.p7b") != null
        }

        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipFile(modl).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory) entries[entry.name] = zip.getInputStream(entry).readBytes()
            }
        }

        val problems = mutableListOf<String>()
        fun require(condition: Boolean, message: String) { if (!condition) problems += message }

        val manifest = entries["module.xml"]?.toString(Charsets.UTF_8)
            ?: throw GradleException("the .modl has no module.xml")

        require("<vendorname>$vendorName</vendorname>" in manifest, "module.xml is missing the vendor name")
        require("<vendorcontactinfo>$vendorUrl</vendorcontactinfo>" in manifest, "module.xml is missing the vendor contact")
        require("<license>" in manifest, "module.xml does not reference a licence file")
        require("<documentation>" in manifest, "module.xml does not reference documentation")
        require("<freeModule>true</freeModule>" in manifest || "<free>true</free>" in manifest,
            "the module is not marked free, so the gateway will run it on the two hour trial timer")
        // "Mantle for Ignition" would be rejected: the Showcase forbids "Ignition" inside a module name.
        require(Regex("<name>([^<]*)</name>").find(manifest)?.groupValues?.get(1)?.contains("Ignition") == false,
            "the module name contains \"Ignition\", which the Showcase naming rules forbid")
        require(Regex("<version>([^<]*)</version>").find(manifest)?.groupValues?.get(1)
            ?.matches(Regex("""\d+\.3\.\d+""")) == true,
            "the module version's middle digit must match the platform (8.3 -> x.3.y); the gateway faults it otherwise")

        require(entries.keys.any { it == "license.html" }, "the .modl has no licence file")
        require(entries.keys.any { it.startsWith("doc/") }, "the .modl has no documentation")
        require(entries.keys.none { it.endsWith(".modl") }, "a .modl is nested inside the .modl")
        // A stale jar from a previous version is how a rename ships twice; the plugin's staging directory keeps
        // old output unless it is cleaned.
        val duplicated = entries.keys.filter { it.endsWith(".jar") }
            .groupBy { it.substringBeforeLast('-') }
            .filterValues { it.size > 1 }
        require(duplicated.isEmpty(), "the same library ships twice: $duplicated — run a clean build")

        if (problems.isNotEmpty()) {
            throw GradleException("${modl.name} is not fit to distribute:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("${modl.name} (${modl.length() / 1024} KiB, ${if (signed) "signed" else "UNSIGNED"}) " +
            "carries its manifest, licence and documentation")
    }
}

tasks.named("check") { dependsOn(checkDependencyLicenses, checkModuleArtifact) }
