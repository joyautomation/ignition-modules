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
            ":gateway" to "G"
        )
    )

    hooks.putAll(
        mapOf(
            "com.joyautomation.ignition.mantle.MantleGatewayHook" to "G"
        )
    )

    // unsigned modules only load on gateways running in developer mode
    // (-Dignition.allowunsignedmodules=true). Set up signing before shipping.
    skipModlSigning.set(true)
}
