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

    @Test
    fun `request body deserializes scalar extent from JSON array`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/OrderRequest.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderRequest:
                    // @Body
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 3.
            """.trimIndent())
        }
        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @POST("/svc/orders")
                    METHOD PUBLIC VOID CreateOrder(INPUT poReq AS com.example.OrderRequest):
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                    END METHOD.
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
                httpMethod = "POST",
                className = "com.example.OrderController",
                ablMethod = "CreateOrder",
                requestDtoClassName = "com.example.OrderRequest"
            )
        )

        assertTrue(shim.contains("oJson:GetJsonArray(\"tags\")"), "shim should read JSON array for extent prop. SHIM:\n$shim")
        assertTrue(shim.contains("oArr_tags:Length"),
            "shim should loop over JSON array length. SHIM:\n$shim")
        assertTrue(shim.contains("oReq:tags[i_tags] = oArr_tags:GetCharacter(i_tags)."),
            "shim should assign each element via GetCharacter. SHIM:\n$shim")
        assertFalse(shim.contains("oJson:GetCharacter(\"tags\")"),
            "shim should not use scalar GetCharacter for extent prop")
    }

    @Test
    fun `response serializes scalar extent as JSON array`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 3.
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

        assertTrue(shim.contains("DEFINE VARIABLE oArr_tags AS Progress.Json.ObjectModel.JsonArray"),
            "shim should declare oArr_tags JsonArray")
        assertTrue(shim.contains("DO i_tags = 1 TO EXTENT(oResult:tags)"),
            "shim should loop over EXTENT bound")
        assertTrue(shim.contains("oArr_tags:Add(oResult:tags[i_tags])."),
            "shim should add each element to array")
        assertTrue(shim.contains("oJson:Add(\"tags\", oArr_tags)."),
            "shim should attach array under prop name")
        assertFalse(shim.contains("oJson:Add(\"tags\", oResult:tags)."),
            "shim should not pass extent ref directly to Add (would stringify)")
    }

    @Test
    fun `response serializes nested DTO extent as array of objects`(@TempDir tmp: Path) {
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
                    DEFINE PUBLIC PROPERTY addrs AS com.example.Address EXTENT 2.
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

        assertTrue(shim.contains("DEFINE VARIABLE oArr_addrs AS Progress.Json.ObjectModel.JsonArray"),
            "shim should declare oArr_addrs JsonArray")
        assertTrue(shim.contains("oArr_addrs:Add(oItem_addrs)."),
            "shim should add per-item object to array")
        assertTrue(shim.contains("oItem_addrs:Add(\"city\", oResult:addrs[i_addrs]:city)."),
            "shim should build per-item JsonObject with nested prop")
        assertTrue(shim.contains("oJson:Add(\"addrs\", oArr_addrs)."),
            "shim should attach array under prop name")
    }

    @Test
    fun `emits INDEX-based query extraction per field without intermediate JsonObject`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE PUBLIC PROPERTY name  AS CHARACTER.
                    DEFINE PUBLIC PROPERTY count AS INTEGER.
            """.trimIndent())
        }
        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC VOID ListOrders(INPUT poReq AS com.example.Order):
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                    END METHOD.
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
                ablMethod = "ListOrders",
                requestDtoClassName = "com.example.Order"
            )
        )

        // No intermediate JsonObject or per-field INDEX/SUBSTRING arithmetic
        assertFalse(shim.contains("oQueryParams"),
            "shim should not declare oQueryParams JsonObject. SHIM:\n$shim")
        assertFalse(shim.contains("iStart_name") || shim.contains("iAmp_name"),
            "shim should not contain per-field INDEX/SUBSTRING arithmetic. SHIM:\n$shim")
        // Shared helper call
        assertTrue(shim.contains("""Tatara.Api.QueryHelper:GetQueryValue(cQuery, "name")"""),
            "shim should call QueryHelper for name. SHIM:\n$shim")
        assertTrue(shim.contains("""Tatara.Api.QueryHelper:GetQueryValue(cQuery, "count")"""),
            "shim should call QueryHelper for count. SHIM:\n$shim")
        // <> ? check
        assertTrue(shim.contains("IF cVal_name <> ? THEN"),
            "shim should guard with <> ?. SHIM:\n$shim")
        // Type casts
        assertTrue(shim.contains("oReq:name = cVal_name."),
            "shim should assign CHARACTER directly. SHIM:\n$shim")
        assertTrue(shim.contains("oReq:count = INTEGER(cVal_count)."),
            "shim should cast INTEGER via INTEGER(). SHIM:\n$shim")
    }

    @Test
    fun `emits 400 error for missing required query parameter`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Required
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC VOID ListOrders(INPUT poReq AS com.example.Order):
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                    END METHOD.
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
                ablMethod = "ListOrders",
                requestDtoClassName = "com.example.Order"
            )
        )

        assertTrue(shim.contains("Missing required query parameter: name"),
            "shim should emit 400 for missing required query. SHIM:\n$shim")
    }
}
