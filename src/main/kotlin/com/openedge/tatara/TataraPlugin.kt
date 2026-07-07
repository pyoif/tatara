package com.openedge.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Plugin is a task-type registry — no configuration logic here.
        // Task types become available to consumers via the plugin classpath.
    }
}
