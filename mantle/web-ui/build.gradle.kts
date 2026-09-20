import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
}

// No Java here, but the java plugin still resolves a compiler, and the system JRE has none.
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// webpack writes into this directory, and the java plugin folds it into the jar; the gateway then serves
// mounted/mantleStatus.js at /res/mantle/mantleStatus.js (see MantleGatewayHook.getMountedResourceFolder).
val generated = layout.buildDirectory.dir("generated-resources")

node {
    version.set("22.14.0")
    download.set(true)
    nodeProjectDir.set(file(projectDir))
}

val bundle by tasks.registering(NpmTask::class) {
    group = "Ignition Module"
    description = "Builds the status page into a UMD bundle."
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.files(fileTree(projectDir).matching {
        include("src/**", "package.json", "package-lock.json", "webpack.config.js", "babel.config.json",
            "tsconfig.json")
    })
    outputs.dir(generated)
}

sourceSets {
    main {
        output.dir(mapOf("builtBy" to listOf(bundle)), generated)
    }
}

tasks.named("processResources") { dependsOn(bundle) }
