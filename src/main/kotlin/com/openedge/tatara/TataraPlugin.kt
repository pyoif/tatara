package com.openedge.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register the PCT download task — consumers don't need to configure it.
        val setupPct = project.tasks.register("setupPct", SetupPctTask::class.java) {
            group = "tatara"
            description = "Downloads latest PCT.jar from GitHub Releases for ABL compilation."
        }

        // Auto-wire: any OeCompileTask depends on setupPct and gets its jar path.
        project.tasks.withType(OeCompileTask::class.java).configureEach {
            dependsOn(setupPct)
            pctJarPath.convention(setupPct.map { it.pctJar.absolutePath })
        }

        // setupPct must run before everything else in the pipeline.
        project.tasks.withType(PrependPackageTask::class.java).configureEach {
            dependsOn(setupPct)
        }
    }
}
