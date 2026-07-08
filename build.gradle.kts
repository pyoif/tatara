plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.pyoif"
version = "1.0.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("com.google.code.gson:gson:2.10.1")
}

gradlePlugin {
    plugins {
        register("tatara") {
            id = "com.pyoif.tatara"
            implementationClass = "com.pyoif.tatara.TataraPlugin"
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
