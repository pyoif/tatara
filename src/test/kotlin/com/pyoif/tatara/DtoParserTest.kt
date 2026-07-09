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
}
