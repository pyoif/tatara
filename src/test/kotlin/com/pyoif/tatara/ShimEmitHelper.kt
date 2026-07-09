package com.pyoif.tatara

import org.gradle.testfixtures.ProjectBuilder
import java.io.File

object ShimEmitHelper {

    fun createTask(): GenerateRoutesTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("testRoutes", GenerateRoutesTask::class.java).get()
    }

    fun invokeWriteShim(
        task: GenerateRoutesTask,
        srcDir: File,
        genDir: File,
        routePath: String,
        routeDef: GenerateRoutesTask.RouteDef
    ): String {
        genDir.mkdirs()
        task.writeShim(routePath, listOf(routeDef), genDir, TEMPLATE, srcDir)

        val fsPath = routePath.replace(Regex("\\{[^}]+\\}"), "_")
        return File(genDir, "$fsPath.cls").readText()
    }

    private val TEMPLATE = """
        USING Progress.Lang.*.
        USING OpenEdge.Web.WebResponseWriter.
        USING OpenEdge.Net.HTTP.StatusCodeEnum.
        USING OpenEdge.Web.WebHandler.
        USING Tatara.Api.*.
        {{USING_BLOCK}}

        BLOCK-LEVEL ON ERROR UNDO, THROW.

        CLASS {{SHIM_CLASS_NAME}} INHERITS WebHandler:
        {{METHOD_HANDLERS}}
        END CLASS.
    """.trimIndent()
}
