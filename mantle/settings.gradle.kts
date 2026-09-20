pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://nexus.inductiveautomation.com/repository/public/")
        }
    }
}

plugins {
    // lets the java toolchain download JDK 17 when the machine doesn't have one
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // the node-gradle plugin downloads the node runtime from here
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        maven {
            url = uri("https://nexus.inductiveautomation.com/repository/public/")
        }
    }
}

rootProject.name = "mantle-ignition"

include(":gateway")
include(":web-ui")
