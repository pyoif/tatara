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

    @Test
    fun `HANDLE prop maps to tt-prefixed temp-table by name`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttData FIELD total AS DECIMAL.
                    // @Array
                    DEFINE PUBLIC PROPERTY data AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"total\""), "should expose total field from ttData. Got:\n$swagger")
        assertFalse(swagger.contains("\"additionalProperties\": true"),
            "should not fall back to generic. Got:\n$swagger")
    }

    @Test
    fun `HANDLE prop with mismatched temp-table name falls back to generic`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttFoo FIELD x AS INTEGER.
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"additionalProperties\": true"),
            "should fall back to generic when no name match. Got:\n$swagger")
        assertFalse(swagger.contains("\"x\""),
            "should not leak unrelated TT fields. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array resolves TT from target class file`(@TempDir tmp: Path) {
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
                    // @Array("com.example.OrderHolder")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/OrderHolder.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderHolder:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should include orderId field from target TT. Got:\n$swagger")
        assertTrue(swagger.contains("\"sku\""), "should include sku field from target TT. Got:\n$swagger")
        assertTrue(swagger.contains("\"type\": \"array\""), "should emit array type. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array with explicit buffer name picks that buffer`(@TempDir tmp: Path) {
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
                    // @Array("com.example.OrderHolder:ttOrders")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/OrderHolder.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderHolder:
                    DEFINE TEMP-TABLE ttOther NO-UNDO
                        FIELD ignored AS CHARACTER.
                    DEFINE TEMP-TABLE ttOrders NO-UNDO
                        FIELD orderId AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should include orderId from ttOrders. Got:\n$swagger")
        assertFalse(swagger.contains("\"ignored\""), "should not include 'ignored' field from ttOther. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array falls back to generic schema when target class missing`(@TempDir tmp: Path) {
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
                    // @Array("com.example.Missing")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"type\": \"array\""), "should still emit array type on fallback. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit generic items on fallback. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table"), "should keep description on fallback. Got:\n$swagger")
    }

    @Test
    fun `current-class @Array with leading-colon explicit buffer`(@TempDir tmp: Path) {
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
                    // @Array(":ttCustom")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
                    DEFINE TEMP-TABLE ttOther  NO-UNDO FIELD skipped AS CHARACTER.
                    DEFINE TEMP-TABLE ttCustom NO-UNDO FIELD a AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"a\""), "should include 'a' from ttCustom. Got:\n$swagger")
        assertFalse(swagger.contains("\"skipped\""), "should not include 'skipped' from ttOther. Got:\n$swagger")
    }

    @Test
    fun `backward-compat @Array without parameter uses tt-PropName convention`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO FIELD orderId AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should resolve ttItems by convention. Got:\n$swagger")
    }

    @Test
    fun `emits typed nested array schema from inline temp-table fields`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Nested:ttLines")
                        FIELD lines   AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/Nested.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Nested:
                    DEFINE TEMP-TABLE ttLines NO-UNDO
                        FIELD lineNo  AS INTEGER
                        FIELD text    AS CHARACTER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"lineNo\""), "should include nested lineNo. Got:\n$swagger")
        assertTrue(swagger.contains("\"text\""), "should include nested text. Got:\n$swagger")
    }

    @Test
    fun `nested @Array falls back to generic when target class missing`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Missing:ttX")
                        FIELD lines AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "outer orderId should be present. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should include generic fallback. Got:\n$swagger")
    }

    @Test
    fun `nested temp-table cycle emits generic schema and does not infinite loop`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Order:ttItems")
                        FIELD children AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "outer orderId should still be present. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array with dashed buffer name resolves fields`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "Budgets.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS Budgets:
                    // @Array("repositories.project.BudgetRepository:tt-budget")
                    DEFINE PUBLIC PROPERTY data       AS HANDLE                 NO-UNDO GET. SET.
                    DEFINE PUBLIC PROPERTY pagination AS DTO.response.Pagination NO-UNDO GET. SET.
                    DEFINE PUBLIC PROPERTY success    AS LOGICAL                NO-UNDO INIT TRUE GET. SET.
                END CLASS.
            """.trimIndent())
        }
        File(src, "repositories/project/BudgetRepository.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS repositories.project.BudgetRepository:
                    DEFINE TEMP-TABLE tt-budget NO-UNDO
                        FIELD id     AS INTEGER
                        FIELD amount AS DECIMAL.
            """.trimIndent())
        }
        File(src, "DTO/response/Pagination.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS DTO.response.Pagination:
                    DEFINE PUBLIC PROPERTY page  AS INTEGER.
                    DEFINE PUBLIC PROPERTY total AS INTEGER.
            """.trimIndent())
        }
        File(src, "BudgetController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS BudgetController:
                    // @GET("/svc/budgets")
                    METHOD PUBLIC Budgets GetBudgets():
                        DEFINE VARIABLE ctrl0 AS BudgetController NO-UNDO.
                        ctrl0 = NEW BudgetController().
                        RETURN NEW Budgets().
                    END METHOD.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "BudgetController", "/svc/budgets")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"id\""), "should include id field from tt-budget. Got:\n$swagger")
        assertTrue(swagger.contains("\"amount\""), "should include amount field from tt-budget. Got:\n$swagger")
    }

    @Test
    fun `HANDLE field without annotation stays as generic handle`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD rawHandle AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "orderId should be present. Got:\n$swagger")
    }
}
