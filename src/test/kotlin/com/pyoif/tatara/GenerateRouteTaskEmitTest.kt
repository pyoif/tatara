package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateRouteTaskEmitTest {

    @Test
    fun `emits WriteJsonObject call for flat response DTO`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
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

        assertTrue(shim.contains("oJson:Add(\"id\", oResult:id)."), "shim should emit direct Add for scalar id")
        assertTrue(shim.contains("oJson:Add(\"name\", oResult:name)."), "shim should emit direct Add for scalar name")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper")
        // Negative assertions: MEMPTR dance must be gone
        assertFalse(shim.contains("oWriter:Open()"), "shim should not open oWriter manually")
        assertFalse(shim.contains("oMemStream"), "shim should not reference oMemStream")
        assertFalse(shim.contains("mPayload"), "shim should not reference mPayload")
        assertFalse(shim.contains("oJson:Write(oMemStream)"), "shim should not serialize oJson to stream manually")
        assertFalse(shim.contains("oWriter:Write(mPayload)"), "shim should not write MEMPTR manually")
        assertFalse(shim.contains("SET-SIZE(mPayload)"), "shim should not free MEMPTR manually")
        assertFalse(shim.contains("oResult:data"), "shim should NOT chunk-write oResult:data")
    }

    @Test
    fun `emits WriteError calls for error catches`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, 404, errCustom:GetMessage(1))."),
            "shim should call WriteError for custom error with status 404")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, errApi:HttpCode, errApi:GetMessage(1))."),
            "shim should call WriteError for ApiError with HttpCode status")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, 500, errApp:GetMessage(1))."),
            "shim should call WriteError for AppError with status 500")
        // Negative: shim should not set StatusCode before the error helper call
        assertFalse(shim.contains("oResponse:StatusCode = errApi:HttpCode."),
            "shim should not set StatusCode for ApiError (helper does it)")
        assertFalse(shim.contains("oResponse:StatusCode = 500."),
            "shim should not set StatusCode for AppError (helper does it)")
    }

    @Test
    fun `emits recursive sub-objects for nested DTO`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("oSub_addr"), "shim should define sub-object variable for nested DTO")
        assertTrue(shim.contains("oSub_addr:Add(\"city\", oResult:addr:city)."), "shim should inline nested DTO prop with chained accessor")
        assertTrue(shim.contains("oSub_addr:Add(\"zip\", oResult:addr:zip)."), "shim should inline nested DTO prop with chained accessor")
        assertTrue(shim.contains("oJson:Add(\"addr\", oSub_addr)."), "shim should attach sub-object to parent under prop name")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper for nested DTO")
    }

    @Test
    fun `emits ReadTempTableAsArray for @Array temp-table prop`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("oJson:Add(\"items\", Tatara.Api.DtoSerializer:ReadTempTableAsArray(oResult:items))."),
            "shim should call ReadTempTableAsArray for @Array")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper for @Array response")
    }

    @Test
    fun `emits ReadTempTableAsObject for @Object temp-table prop`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("oJson:Add(\"summary\", Tatara.Api.DtoSerializer:ReadTempTableAsObject(oResult:summary))."),
            "shim should call ReadTempTableAsObject for @Object")
    }
}
