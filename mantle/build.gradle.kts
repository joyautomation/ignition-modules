plugins {
    base
    id("io.ia.sdk.modl") version("0.5.0")
}

val sdkVersion by extra("8.3.9")

allprojects {
    version = "0.1.0-SNAPSHOT"
    group = "com.joyautomation.mantle"
}

ignitionModule {
    fileName.set("Mantle-Ignition")
    name.set("Mantle for Ignition")
    id.set("com.joyautomation.mantle")
    moduleVersion.set("${project.version}")
    moduleDescription.set(
        "Sparkplug B host application. Tags are created as they are born, historized by default, " +
            "and customized in place."
    )
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
