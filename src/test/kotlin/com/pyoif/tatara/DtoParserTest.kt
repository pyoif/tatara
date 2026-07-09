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
}
