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
        val tempTableKind: TempTableKind = TempTableKind.NONE,
        val tempTableClass: String? = null,
        val tempTableName: String? = null
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
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+([\w-]+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)(?:\("([^"]*)"\))?""")
    private val ttDefRegex = Regex(
        """(?is)DEFINE\s+TEMP-TABLE\s+([\w-]+)((?:[^."]|"[^"]*")+?)\."""
    )
    private val fieldDefRegex = Regex(
        """(?i)FIELD\s+([\w-]+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
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
        var currentTempTableClass: String? = null
        var currentTempTableName: String? = null

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
                    "object" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.OBJECT
                        isReq = false
                        val (cls, name) = parseTempTableParam(m.groupValues[2])
                        currentTempTableClass = cls
                        currentTempTableName = name
                    }
                    "array" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.ARRAY
                        isReq = false
                        val (cls, name) = parseTempTableParam(m.groupValues[2])
                        currentTempTableClass = cls
                        currentTempTableName = name
                    }
                    "temptable" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.ARRAY
                        isReq = false
                        // @TempTable is a bare alias — drop any parameter syntax.
                    }
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
                    tempTableKind = tempTableKind,
                    tempTableClass = currentTempTableClass,
                    tempTableName = currentTempTableName
                ))
                // Reset @Required and @TempTable kind — location sticks until next @Path/@Query/@Body
                isReq = false
                isTempTable = false
                tempTableKind = TempTableKind.NONE
                currentTempTableClass = null
                currentTempTableName = null
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

            var isTempTable = false
            var tempTableKind = TempTableKind.NONE
            var currentTempTableClass: String? = null
            var currentTempTableName: String? = null

            body.lines().forEach { line ->
                val trimmed = line.trim()

                annotationRegex.findAll(trimmed).forEach { m ->
                    when (m.groupValues[1].lowercase()) {
                        "object" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.OBJECT
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "array" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "temptable" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            // bare alias — drop any parameter
                        }
                    }
                }

                fieldDefRegex.find(trimmed)?.let { m ->
                    val name = m.groupValues[1]
                    val ablType = m.groupValues[2]
                    val isExtent = m.groups[3]?.value != null
                    val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()

                    val promoteToTempTable = isTempTable && ablType.uppercase() == "HANDLE"
                    // Field-level: name follows the tt<FieldName> convention when the user
                    // didn't specify a class (i.e. "search current class for tt<FieldName>").
                    // When the user explicitly gave a class but no name, leave the name null
                    // so the OpenAPI task applies the convention uniformly.
                    val effectiveName = when {
                        !promoteToTempTable                          -> null
                        currentTempTableName != null                 -> currentTempTableName
                        currentTempTableClass == null                -> "tt" + name.replaceFirstChar { it.uppercase() }
                        else                                         -> null
                    }
                    properties.add(DtoProperty(
                        name = name,
                        ablType = ablType,
                        isExtent = isExtent,
                        extentSize = extentSize,
                        isTempTable = promoteToTempTable,
                        tempTableKind = if (promoteToTempTable) tempTableKind else TempTableKind.NONE,
                        tempTableClass = if (promoteToTempTable) currentTempTableClass else null,
                        tempTableName = effectiveName
                    ))

                    // Reset after each field is emitted
                    isTempTable = false
                    tempTableKind = TempTableKind.NONE
                    currentTempTableClass = null
                    currentTempTableName = null
                }
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

    private data class TempTableParam(val tempTableClass: String?, val tempTableName: String?)

    private fun parseTempTableParam(raw: String?): TempTableParam {
        if (raw.isNullOrEmpty()) return TempTableParam(null, null)
        val colon = raw.indexOf(':')
        return when {
            colon < 0  -> TempTableParam(raw, null)
            colon == 0 -> TempTableParam(null, raw.substring(1))
            else       -> TempTableParam(raw.substring(0, colon), raw.substring(colon + 1))
        }
    }
}
