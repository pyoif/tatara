package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateOpenApiTaskTest {

    private fun runGenerateOpenApi(
        src: File,
        handlers: File,
        out: File
    ) {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val task = project.tasks.register("testOpenApi", GenerateOpenApiTask::class.java).get()

        task.generatedDir.set(src)
        task.packagedDir.set(src)
        task.handlersDir.set(handlers)
        task.swaggerFile.set(out.resolve("swagger.json"))
        task.apiTitle.set("Test API")
        task.apiVersion.set("1.0.0")
        task.apiServerUrl.set("http://localhost")

        out.mkdirs()
        task.generate()
    }

    private fun writeHandlers(dir: File, serviceName: String, className: String, uri: String) {
        dir.mkdirs()
        File(dir, "$serviceName.handlers").writeText("""
            {
                "version": "2.0",
                "serviceName": "$serviceName",
                "handlers": [
                    { "uri": "$uri", "class": "$className", "enabled": true }
                ]
            }
        """.trimIndent())
    }

    @Test
    fun `emits array schema for @Array temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC com.example.Order GetOrder():
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"type\": \"array\""), "should emit array type. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit additionalProperties. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table"), "should include temp-table description. Got:\n$swagger")
    }

    @Test
    fun `emits object schema for @Object temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC com.example.Order GetOrder():
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit additionalProperties. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table (single-row)"), "should include single-row description. Got:\n$swagger")
    }
}
