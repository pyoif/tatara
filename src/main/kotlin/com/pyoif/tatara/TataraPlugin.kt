package com.pyoif.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val setupPct = project.tasks.register("setupPct", SetupPctTask::class.java) {
            group = "tatara"
            description = "Downloads latest PCT.jar from GitHub Releases for ABL compilation."
        }

        project.tasks.withType(OeCompileTask::class.java).configureEach {
            dependsOn(setupPct)
            pctJarPath.convention(setupPct.map { it.pctJar.absolutePath })
        }

        project.tasks.withType(PrependPackageTask::class.java).configureEach {
            dependsOn(setupPct)
        }

        project.tasks.register("generateOpenApi", GenerateOpenApiTask::class.java) {
            group = "tatara"
            description = "Generates OpenAPI 3.0 swagger.json from handler annotations and DTO classes"
            apiServerUrl.convention("/${project.name}/web")
        }

        val copySwaggerUi = project.tasks.register("copySwaggerUi", Copy::class.java) {
            group = "tatara"
            description = "Copies swagger/index.html to pasoeTemplate/docs/swagger/ for WAR bundling"
            from(project.rootDir.resolve("swagger/index.html"))
            into(project.layout.buildDirectory.dir("resources/main/pasoeTemplate/docs/swagger"))
        }

        project.tasks.withType(PackageWarTask::class.java).configureEach {
            dependsOn(copySwaggerUi)
        }
    }
}
