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
