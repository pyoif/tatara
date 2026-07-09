package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateRouteTaskEmitTest {

    @Test
    fun `emits DtoSerializer call for flat response DTO`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("Tatara.Api.DtoSerializer:ToJsonObject"), "shim should call DtoSerializer")
        assertTrue(shim.contains("\"id\""),  "shim should reference id prop")
        assertTrue(shim.contains("\"name\""),"shim should reference name prop")
        assertTrue(shim.contains("oJson:Write(oWriter)"), "shim should write oJson to oWriter")
        assertTrue(shim.contains("oResponse:ContentType = \"application/json\""), "shim should set content type")
        assertFalse(shim.contains("oResult:data"), "shim should NOT chunk-write oResult:data")
        assertFalse(shim.contains("oResponse:Entity = oJson"), "shim should not set Entity (writes via oWriter)")
    }

    @Test
    fun `emits recursive DtoSerializer call for nested response DTO`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
                    DEFINE PUBLIC PROPERTY zip  AS CHARACTER.
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

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("oResult:addr"), "shim should reference oResult:addr for nested prop")
        assertTrue(shim.contains("\"city\""), "shim should include nested prop city")
        assertTrue(shim.contains("\"zip\""),  "shim should include nested prop zip")
    }

    @Test
    fun `opens oWriter once at top and writes error message to it`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/NotFoundError.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.NotFoundError INHERITS Progress.Lang.AppError:
                END CLASS.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users/{id}",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users/{id}",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                pathParams = listOf("id"),
                responseDtoClassName = "com.example.User",
                errorResponses = mapOf(404 to "com.example.NotFoundError")
            )
        )

        // oWriter:Open() called exactly once, right after initialization
        assertEquals(1, shim.split("oWriter:Open()").size - 1, "oWriter:Open() should appear exactly once")
        val openIdx = shim.indexOf("oWriter:Open()")
        val initIdx = shim.indexOf("oWriter = NEW OpenEdge.Web.WebResponseWriter")
        assertTrue(openIdx > initIdx, "oWriter:Open() should come after oWriter initialization")
        assertTrue(openIdx - initIdx < 200, "oWriter:Open() should be right after init, not far away")

        // Error path writes message via oJson:Write(oWriter), not oResponse:Entity
        assertTrue(shim.contains("oJson:Add(\"error\", errApi:GetMessage(1))"), "ApiError catch should add error message")
        assertTrue(shim.contains("oJson:Add(\"error\", errApp:GetMessage(1))"), "AppError catch should add error message")
        assertTrue(shim.contains("oJson:Add(\"error\", errCustom:GetMessage(1))"), "Custom error catch should add error message")
        assertFalse(shim.contains("oResponse:Entity = errCustom"), "should not assign custom error to Entity")
        assertFalse(shim.contains("oResponse:Entity = NEW Tatara.Api.ErrorResponse"), "should not construct ErrorResponse")
    }

    @Test
    fun `emits inline Add for @Array temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/orders",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/orders",
                httpMethod = "GET",
                className = "com.example.OrderController",
                ablMethod = "GetOrder",
                responseDtoClassName = "com.example.Order"
            )
        )

        assertTrue(shim.contains("oJson:Add(\"items\", oResult:items)."),
            "shim should emit inline Add for @Array temp-table prop")
        assertFalse(shim.contains("Tatara.Api.DtoSerializer:ToJsonObject"),
            "shim should not call DtoSerializer for temp-table prop")
    }

    @Test
    fun `emits Read path for @Object temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/orders",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/orders",
                httpMethod = "GET",
                className = "com.example.OrderController",
                ablMethod = "GetOrder",
                responseDtoClassName = "com.example.Order"
            )
        )

        assertTrue(shim.contains("oJson:Add(\"summary\", NEW Progress.Json.ObjectModel.JsonObject())."),
            "shim should construct empty JsonObject for @Object")
        assertTrue(shim.contains("oJson:GetJsonObject(\"summary\"):Read(oResult:summary)."),
            "shim should call Read for @Object")
    }
}
