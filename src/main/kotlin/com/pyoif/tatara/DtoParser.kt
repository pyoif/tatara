package com.pyoif.tatara

import java.io.File

object DtoParser {
    enum class ParamLocation { PATH, QUERY, BODY, UNKNOWN }
    enum class TempTableKind { NONE, OBJECT, ARRAY }

    data class DtoProperty(
        val name: String,
        val ablType: String,
        val isRequired: Boolean = false,
        val location: ParamLocation = ParamLocation.UNKNOWN,
        val isExtent: Boolean = false,
        val extentSize: Int? = null,
        val isDto: Boolean = false,
        val nested: DtoInfo? = null,
        val isTempTable: Boolean = false,
        val tempTableKind: TempTableKind = TempTableKind.NONE
    )

    data class DtoInfo(
        val properties: List<DtoProperty> = emptyList()
    ) {
        companion object {
            val EMPTY = DtoInfo()
        }
    }

    data class InlineTempTable(
        val bufferName: String,
        val fields: DtoInfo
    )

    private val propDefRegex = Regex(
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)""")
    private val ttDefRegex = Regex(
        """(?is)DEFINE\s+TEMP-TABLE\s+(\w+)([^.]+?)\."""
    )
    private val fieldDefRegex = Regex(
        """(?i)FIELD\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )

    fun parse(
        dtoClassName: String,
        srcRoot: File,
        visited: MutableSet<String> = mutableSetOf()
    ): DtoInfo {
        val file = resolveFile(dtoClassName, srcRoot) ?: return DtoInfo.EMPTY
        visited.add(dtoClassName)

        val properties = mutableListOf<DtoProperty>()
        val lines = file.readText().lines()

        var isReq = false
        var loc = ParamLocation.UNKNOWN
        var isTempTable = false
        var tempTableKind = TempTableKind.NONE

        val primitives = setOf(
            "CHARACTER", "LONGCHAR", "INTEGER", "INT64", "DECIMAL",
            "LOGICAL", "DATE", "DATETIME", "DATETIME-TZ",
            "HANDLE", "RAW", "MEMPTR", "BLOB", "CLOB"
        )

        for (line in lines) {
            val trimmed = line.trim()
            annotationRegex.findAll(trimmed).forEach { m ->
                when (m.groupValues[1].lowercase()) {
                    "required" -> isReq = true
                    "path" -> { loc = ParamLocation.PATH; isReq = false }
                    "query" -> { loc = ParamLocation.QUERY; isReq = false }
                    "body" -> { loc = ParamLocation.BODY; isReq = false }
                    "object" -> { isTempTable = true; tempTableKind = TempTableKind.OBJECT; isReq = false }
                    "array", "temptable" -> { isTempTable = true; tempTableKind = TempTableKind.ARRAY; isReq = false }
                }
            }

            propDefRegex.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                val ablType = m.groupValues[2]
                val isExtent = m.groups[3]?.value != null
                val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()

                var isDto = false
                var nested: DtoInfo? = null
                if (!isTempTable) {
                    val upperType = ablType.uppercase()
                    val isScalar = primitives.contains(upperType) || upperType == "VOID"
                    if (!isScalar) {
                        if (ablType in visited) {
                            isDto = false
                            nested = null
                        } else {
                            isDto = true
                            nested = parse(ablType, srcRoot, visited)
                        }
                    }
                }

                properties.add(DtoProperty(
                    name = name,
                    ablType = ablType,
                    isRequired = isReq,
                    location = loc,
                    isExtent = isExtent,
                    extentSize = extentSize,
                    isDto = isDto,
                    nested = nested,
                    isTempTable = isTempTable,
                    tempTableKind = tempTableKind
                ))
                // Reset @Required and @TempTable kind — location sticks until next @Path/@Query/@Body
                isReq = false
                isTempTable = false
                tempTableKind = TempTableKind.NONE
            }
        }
        return DtoInfo(properties)
    }

    fun parseInlineTempTable(dtoClassName: String, srcRoot: File): InlineTempTable? =
        parseAllInlineTempTables(dtoClassName, srcRoot).firstOrNull()

    fun parseInlineTempTableByName(
        dtoClassName: String,
        srcRoot: File,
        bufferName: String
    ): InlineTempTable? = parseAllInlineTempTables(dtoClassName, srcRoot)
        .firstOrNull { it.bufferName == bufferName }

    private fun parseAllInlineTempTables(
        dtoClassName: String,
        srcRoot: File
    ): List<InlineTempTable> {
        val file = resolveFile(dtoClassName, srcRoot) ?: return emptyList()
        val content = file.readText()
        val results = mutableListOf<InlineTempTable>()
        ttDefRegex.findAll(content).forEach { ttMatch ->
            val bufferName = ttMatch.groupValues[1]
            val body = ttMatch.groupValues[2]
            val properties = mutableListOf<DtoProperty>()
            fieldDefRegex.findAll(body).forEach { m ->
                val name = m.groupValues[1]
                val ablType = m.groupValues[2]
                val isExtent = m.groups[3]?.value != null
                val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()
                properties.add(DtoProperty(
                    name = name,
                    ablType = ablType,
                    isExtent = isExtent,
                    extentSize = extentSize
                ))
            }
            results.add(InlineTempTable(bufferName, DtoInfo(properties)))
        }
        return results
    }

    private fun resolveFile(dtoClassName: String, srcRoot: File): File? {
        val relativePath = dtoClassName.replace(".", "/") + ".cls"
        val file = File(srcRoot, relativePath)
        return if (file.exists()) file else null
    }
}
