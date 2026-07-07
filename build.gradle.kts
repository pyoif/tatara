plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.openedge"
version = "1.0.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("progress.openedge.abl-base:progress.openedge.abl-base.gradle.plugin:2.4.0")
}

gradlePlugin {
    plugins {
        register("tatara") {
            id = "com.openedge.tatara"
            implementationClass = "com.openedge.tatara.TataraPlugin"
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
