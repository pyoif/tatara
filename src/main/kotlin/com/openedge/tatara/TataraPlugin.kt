package com.openedge.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Task types become available to consumers via the plugin classpath.
        // No configuration needed — all logic lives in each Task class.
    }
}
