package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DtoParserTest {

    @Test
    fun `parses flat DTO with primitive types`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        assertEquals(2, info.properties.size)
        assertEquals("id",   info.properties[0].name)
        assertEquals("INTEGER", info.properties[0].ablType)
        assertFalse(info.properties[0].isDto)
        assertEquals("name", info.properties[1].name)
        assertEquals("CHARACTER", info.properties[1].ablType)
        assertFalse(info.properties[1].isDto)
    }

    @Test
    fun `parses nested DTO recursively`(@TempDir tmp: Path) {
        val src = tmp.toFile()
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
                    DEFINE PUBLIC PROPERTY id     AS INTEGER.
                    DEFINE PUBLIC PROPERTY addr   AS com.example.Address.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val addrProp = info.properties.find { it.name == "addr" }!!
        assertTrue(addrProp.isDto)
        assertNotNull(addrProp.nested)
        assertEquals(1, addrProp.nested!!.properties.size)
        assertEquals("city", addrProp.nested!!.properties[0].name)
    }

    @Test
    fun `breaks cycles by marking repeated class as not-dto`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/A.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.A:
                    DEFINE PUBLIC PROPERTY b AS com.example.B.
            """.trimIndent())
        }
        File(src, "com/example/B.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.B:
                    DEFINE PUBLIC PROPERTY a AS com.example.A.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.A", src)
        val bProp = info.properties[0]
        assertTrue(bProp.isDto)
        assertNotNull(bProp.nested)
        val aProp = bProp.nested!!.properties[0]
        assertEquals("a", aProp.name)
        assertFalse(aProp.isDto)  // cycle broken
        assertNull(aProp.nested)
    }

    @Test
    fun `returns empty info for missing class`(@TempDir tmp: Path) {
        val info = DtoParser.parse("com.example.Missing", tmp.toFile())
        assertTrue(info.properties.isEmpty())
    }

    @Test
    fun `recognizes @TempTable annotation as ARRAY by default`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @TempTable
                    DEFINE PUBLIC PROPERTY orders AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        assertEquals(1, info.properties.size)
        val prop = info.properties[0]
        assertEquals("orders", prop.name)
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertFalse(prop.isDto)
    }

    @Test
    fun `recognizes @Array annotation explicitly`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
    }

    @Test
    fun `recognizes @Object annotation`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, prop.tempTableKind)
    }

    @Test
    fun `captures fixed EXTENT size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 5.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isExtent)
        assertEquals(5, prop.extentSize)
    }

    @Test
    fun `captures unfixed EXTENT with null size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isExtent)
        assertNull(prop.extentSize)
    }

    @Test
    fun `non-extent prop has null size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertFalse(prop.isExtent)
        assertNull(prop.extentSize)
    }

    @Test
    fun `HANDLE prop without annotation is not a temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY rawHandle AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertFalse(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, prop.tempTableKind)
    }

    @Test
    fun `parseInlineTempTable extracts fields from DEFINE TEMP-TABLE`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)
        assertNotNull(tt)
        assertEquals("ttItems", tt!!.bufferName)
        assertEquals(2, tt.fields.properties.size)
        assertEquals("orderId", tt.fields.properties[0].name)
        assertEquals("INTEGER", tt.fields.properties[0].ablType)
        assertEquals("sku", tt.fields.properties[1].name)
        assertEquals("CHARACTER", tt.fields.properties[1].ablType)
    }

    @Test
    fun `parseInlineTempTable returns null when no TEMP-TABLE`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.User", src)
        assertNull(tt)
    }

    @Test
    fun `parseInlineTempTable captures EXTENT on field`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD tags AS CHARACTER EXTENT 3.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)
        assertNotNull(tt)
        val field = tt!!.fields.properties[0]
        assertTrue(field.isExtent)
        assertEquals(3, field.extentSize)
    }

    @Test
    fun `parseInlineTempTableByName returns matching temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER.
                    DEFINE TEMP-TABLE ttOther FIELD x AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.Order", src, "ttItems")
        assertNotNull(tt)
        assertEquals("ttItems", tt!!.bufferName)
        assertEquals("orderId", tt.fields.properties[0].name)
    }

    @Test
    fun `parseInlineTempTableByName returns null when name not found`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.Order", src, "ttNotPresent")
        assertNull(tt)
    }

    @Test
    fun `parseInlineTempTableByName returns null when no temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.User", src, "ttAnything")
        assertNull(tt)
    }

    @Test
    fun `parses @Array with class name only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array("com.example.Order")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertEquals("com.example.Order", prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `parses @Object with class name only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Object("com.example.Summary")
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, prop.tempTableKind)
        assertEquals("com.example.Summary", prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `parses @Array with class and buffer name`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array("com.example.Order:ttOrders")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals("com.example.Order", prop.tempTableClass)
        assertEquals("ttOrders", prop.tempTableName)
    }

    @Test
    fun `parses @Array with dashed buffer name like tt-budget`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "repositories/project/BudgetRepository.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS repositories.project.BudgetRepository:
                    DEFINE TEMP-TABLE tt-budget NO-UNDO
                        FIELD id     AS INTEGER
                        FIELD amount AS DECIMAL.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("repositories.project.BudgetRepository", src, "tt-budget")
        assertNotNull(tt)
        assertEquals("tt-budget", tt!!.bufferName)
        assertEquals(2, tt.fields.properties.size)
        assertEquals("id", tt.fields.properties[0].name)
        assertEquals("amount", tt.fields.properties[1].name)
    }

    @Test
    fun `parses @Array with current class and explicit buffer via leading colon`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array(":ttCustom")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertNull(prop.tempTableClass)
        assertEquals("ttCustom", prop.tempTableName)
    }

    @Test
    fun `parses @Array without parameter preserves null fields`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertNull(prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `TempTable annotation drops any parameter syntax`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @TempTable("com.example.X:ttY")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertNull(prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `parses nested @Array on FIELD line with class only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Nested")
                        FIELD lines   AS HANDLE
                        FIELD sku     AS CHARACTER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val linesField = tt.fields.properties.find { it.name == "lines" }!!
        assertTrue(linesField.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, linesField.tempTableKind)
        assertEquals("com.example.Nested", linesField.tempTableClass)
        assertNull(linesField.tempTableName)
    }

    @Test
    fun `parses nested @Object on FIELD line with class and buffer name`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Object("com.example.Nested:ttHeader")
                        FIELD summary AS HANDLE.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val summaryField = tt.fields.properties.find { it.name == "summary" }!!
        assertTrue(summaryField.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, summaryField.tempTableKind)
        assertEquals("com.example.Nested", summaryField.tempTableClass)
        assertEquals("ttHeader", summaryField.tempTableName)
    }

    @Test
    fun `parses bare @Array on FIELD line using convention`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Array
                        FIELD lines   AS HANDLE.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val linesField = tt.fields.properties.find { it.name == "lines" }!!
        assertTrue(linesField.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, linesField.tempTableKind)
        assertNull(linesField.tempTableClass)
        assertEquals("ttLines", linesField.tempTableName)
    }

    @Test
    fun `HANDLE field without annotation is not a nested temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD raw   AS HANDLE
                        FIELD orderId AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val rawField = tt.fields.properties.find { it.name == "raw" }!!
        assertFalse(rawField.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, rawField.tempTableKind)
    }

    @Test
    fun `non-HANDLE field with @Array annotation is still not a temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Array
                        FIELD label AS CHARACTER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val labelField = tt.fields.properties.find { it.name == "label" }!!
        assertFalse(labelField.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, labelField.tempTableKind)
    }

    @Test
    fun `parses DATASET-HANDLE property type`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE PUBLIC PROPERTY data AS DATASET-HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.Order", src)
        val prop = info.properties[0]
        assertTrue(prop.isDataset)
    }

    @Test
    fun `parses DEFINE DATASET FOR t1, t2`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "repositories/project/OrderRepository.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS repositories.project.OrderRepository:
                    DEFINE TEMP-TABLE ttOrder NO-UNDO FIELD orderId AS INTEGER.
                    DEFINE TEMP-TABLE ttLine  NO-UNDO FIELD lineNo AS INTEGER.
                    DEFINE DATASET dsOrder FOR ttOrder, ttLine
                        DATA-RELATION rel1 FOR ttOrder, ttLine RELATION-FIELDS(orderId, orderId).
            """.trimIndent())
        }
        val ds = DtoParser.parseDataset("repositories.project.OrderRepository", src, "dsOrder")
        assertNotNull(ds)
        assertEquals("dsOrder", ds!!.name)
        assertEquals("ttOrder", ds.parentTable)
        assertEquals(listOf("ttOrder", "ttLine"), ds.tables)
    }

    @Test
    fun `parses TT-level SERIALIZE-NAME`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttOrder SERIALIZE-NAME "order" NO-UNDO
                        FIELD orderId AS INTEGER.
            """.trimIndent())
        }
        val result = DtoParser.parseInlineTempTableRaw("com.example.Order", src, "ttOrder")
        assertNotNull(result)
        assertEquals("ttOrder", result!!.first.bufferName)
        assertEquals("order", result.second)
    }

    @Test
    fun `parses field-level SERIALIZE-NAME`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttOrder SERIALIZE-NAME "order" NO-UNDO
                        FIELD orderId AS INTEGER SERIALIZE-NAME "id"
                        FIELD total   AS DECIMAL.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val orderId = tt.fields.properties.find { it.name == "orderId" }!!
        val total = tt.fields.properties.find { it.name == "total" }!!
        assertEquals("id", orderId.serializeName)
        assertNull(total.serializeName)
    }

    @Test
    fun `omits SERIALIZE-HIDDEN fields`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttOrder NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD secret  AS CHARACTER SERIALIZE-HIDDEN
                        FIELD total   AS DECIMAL.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        assertEquals(2, tt.fields.properties.size)
        assertEquals("orderId", tt.fields.properties[0].name)
        assertEquals("total", tt.fields.properties[1].name)
        val secret = tt.fields.properties.find { it.name == "secret" }
        assertNull(secret)
    }
}

