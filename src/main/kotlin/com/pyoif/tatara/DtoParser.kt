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

    private val propDefRegex = Regex(
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)""")

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

    private fun resolveFile(dtoClassName: String, srcRoot: File): File? {
        val relativePath = dtoClassName.replace(".", "/") + ".cls"
        val file = File(srcRoot, relativePath)
        return if (file.exists()) file else null
    }
}
