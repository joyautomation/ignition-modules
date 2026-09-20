plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val sdkVersion: String by rootProject.extra

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:$sdkVersion")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:$sdkVersion")
    // provided by the gateway at runtime; see the modlImplementation excludes below
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    // modlImplementation bundles the jar (and its transitives) into the .modl. The gateway owns logging, so
    // slf4j and logback stay out: a second copy in the module classloader breaks it.
    modlImplementation("org.eclipse.tahu:tahu-core:1.0.21") {
        // we only use tahu for the payload codec; the MQTT client is HiveMQ
        exclude(group = "org.eclipse.paho")
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    modlImplementation("com.hivemq:hivemq-mqtt-client:1.4.0") {
        exclude(group = "org.slf4j")
    }

    // carries mounted/mantle.js into the .modl
    modlImplementation(project(":web-ui"))

    testImplementation("com.inductiveautomation.ignitionsdk:ignition-common:$sdkVersion")
    testImplementation("com.inductiveautomation.ignitionsdk:gateway-api:$sdkVersion")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// A Sparkplug edge node to test against:
//   ./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1"
tasks.register<JavaExec>("simulate") {
    group = "verification"
    description = "Runs a simulated Sparkplug B edge node against a broker."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.joyautomation.ignition.mantle.sim.EdgeSimulator")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}
