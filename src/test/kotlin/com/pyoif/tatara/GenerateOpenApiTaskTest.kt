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

    @Test
    fun `ErrorResponse schema only contains error key`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/UserController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.UserController:
                    // @GET("/svc/users")
                    METHOD PUBLIC com.example.User GetUser():
                        DEFINE VARIABLE ctrl0 AS com.example.UserController NO-UNDO.
                        ctrl0 = NEW com.example.UserController().
                        RETURN NEW com.example.User().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.UserController", "/svc/users")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertFalse(swagger.contains("\"message\": \"string\""), "ErrorResponse should not have message key. Got:\n$swagger")
        assertTrue(swagger.contains("\"ErrorResponse\""), "should still include ErrorResponse. Got:\n$swagger")
    }

    @Test
    fun `custom error response uses ErrorResponse schema not custom class`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/UserController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.UserController:
                    // @GET("/svc/users")
                    // @Response(404, com.example.NotFoundError)
                    METHOD PUBLIC com.example.User GetUser():
                        DEFINE VARIABLE ctrl0 AS com.example.UserController NO-UNDO.
                        ctrl0 = NEW com.example.UserController().
                        RETURN NEW com.example.User().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.UserController", "/svc/users")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"404\""), "should have 404 response. Got:\n$swagger")
        assertFalse(swagger.contains("#/components/schemas/NotFoundError"),
            "should not reference custom DTO class for error. Got:\n$swagger")
        assertTrue(swagger.contains("#/components/schemas/ErrorResponse"),
            "should reference ErrorResponse for 404. Got:\n$swagger")
    }

    @Test
    fun `nested DTO prop schema is added to schemas`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/UserController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.UserController:
                    // @GET("/svc/users")
                    METHOD PUBLIC com.example.User GetUser():
                        DEFINE VARIABLE ctrl0 AS com.example.UserController NO-UNDO.
                        ctrl0 = NEW com.example.UserController().
                        RETURN NEW com.example.User().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY addr AS com.example.Address.
            """.trimIndent())
        }
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.UserController", "/svc/users")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"Address\""), "Address schema should be added. Got:\n$swagger")
        assertTrue(swagger.contains("\"city\""), "Address schema should contain city prop. Got:\n$swagger")
        assertTrue(swagger.contains("#/components/schemas/Address"),
            "User schema should \$ref Address. Got:\n$swagger")
    }

    @Test
    fun `emits typed array schema from inline temp-table fields`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should expose orderId field. Got:\n$swagger")
        assertTrue(swagger.contains("\"sku\""), "should expose sku field. Got:\n$swagger")
        assertFalse(swagger.contains("\"additionalProperties\": true"),
            "should not fall back to generic schema when inline temp-table is present. Got:\n$swagger")
    }

    @Test
    fun `falls back to generic schema when no inline temp-table`(@TempDir tmp: Path) {
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
        assertTrue(swagger.contains("\"additionalProperties\": true"),
            "should fall back to generic schema. Got:\n$swagger")
    }
}
