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
        val tempTableName: String? = null,
        val isDataset: Boolean = false,
        val serializeName: String? = null,
        val serializeHidden: Boolean = false
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
        val fields: DtoInfo,
        val serializeName: String? = null,
        val kind: TempTableKind = TempTableKind.NONE
    )

    data class DatasetInfo(
        val name: String,
        val tables: List<String>
    ) {
        val parentTable: String get() = tables.firstOrNull() ?: ""
    }

    private val propDefRegex = Regex(
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+([\w-]+)\s+AS\s+([\w-]+(?:[.\-][\w-]+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)(?:\("([^"]*)"\))?""")
    private val ttDefRegex = Regex(
        """(?is)DEFINE\s+TEMP-TABLE\s+([\w-]+)((?:[^."]|"[^"]*")+?)\."""
    )
    private val fieldDefRegex = Regex(
        """(?i)FIELD\s+([\w-]+)\s+AS\s+([\w-]+(?:[.\-][\w-]+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
    )
    private val datasetDefRegex = Regex(
        """(?is)DEFINE\s+DATASET\s+([\w-]+)\s+FOR\s+([\w\-, ]+?)(?=\s*(?:DATA-RELATION|\.|$))"""
    )
    private val serializeNameRegex = Regex("""(?i)SERIALIZE-NAME\s+"([^"]+)"""")
    private val serializeHiddenRegex = Regex("""(?i)SERIALIZE-HIDDEN""")

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

                val isDataset = if (isTempTable && currentTempTableClass != null && currentTempTableName != null) {
                    parseDataset(currentTempTableClass!!, srcRoot, currentTempTableName!!) != null
                } else false

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
                    tempTableName = currentTempTableName,
                    isDataset = isDataset
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

    fun parseInlineTempTableRaw(
        dtoClassName: String,
        srcRoot: File,
        bufferName: String
    ): Pair<InlineTempTable, String?>? = parseAllInlineTempTables(dtoClassName, srcRoot)
        .firstOrNull { it.bufferName == bufferName }
        ?.let { it to it.serializeName }

    fun parseDataset(dtoClassName: String, srcRoot: File, datasetName: String): DatasetInfo? {
        val file = resolveFile(dtoClassName, srcRoot) ?: return null
        val content = file.readText()
        val match = datasetDefRegex.find(content) ?: return null
        if (match.groupValues[1] != datasetName) return null
        val tablesRaw = match.groupValues[2]
        val tables = tablesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tables.isEmpty()) return null
        return DatasetInfo(datasetName, tables)
    }

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

            // TT-level SERIALIZE-NAME is on the DEFINE TEMP-TABLE line, not in the body.
            val ttSerializeName = findTtSerializeName(content, bufferName)

            var isTempTable = false
            var tempTableKind = TempTableKind.NONE
            var currentTempTableClass: String? = null
            var currentTempTableName: String? = null
            // TT-level kind: set by the first @Object/@Array/@TempTable annotation in the body.
            // Used when the TT is referenced as a child in a dataset.
            var ttLevelKind = TempTableKind.NONE

            body.lines().forEach { line ->
                val trimmed = line.trim()

                annotationRegex.findAll(trimmed).forEach { m ->
                    when (m.groupValues[1].lowercase()) {
                        "object" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.OBJECT
                            if (ttLevelKind == TempTableKind.NONE) ttLevelKind = TempTableKind.OBJECT
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "array" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            if (ttLevelKind == TempTableKind.NONE) ttLevelKind = TempTableKind.ARRAY
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "temptable" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            if (ttLevelKind == TempTableKind.NONE) ttLevelKind = TempTableKind.ARRAY
                            // bare alias — drop any parameter
                        }
                    }
                }

                fieldDefRegex.find(trimmed)?.let { m ->
                    val name = m.groupValues[1]
                    val ablType = m.groupValues[2]
                    val isExtent = m.groups[3]?.value != null
                    val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()

                    if (serializeHiddenRegex.containsMatchIn(trimmed)) {
                        return@let
                    }

                    val fieldSerializeName = serializeNameRegex.find(trimmed)?.groupValues?.get(1)

                    val promoteToTempTable = isTempTable && ablType.uppercase() == "HANDLE"
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
                        tempTableName = effectiveName,
                        serializeName = fieldSerializeName
                    ))

                    isTempTable = false
                    tempTableKind = TempTableKind.NONE
                    currentTempTableClass = null
                    currentTempTableName = null
                }
            }

            results.add(InlineTempTable(bufferName, DtoInfo(properties), ttSerializeName, ttLevelKind))
        }
        return results
    }

    private fun findTtSerializeName(content: String, bufferName: String): String? {
        val regex = Regex(
            """(?im)^\s*DEFINE\s+TEMP-TABLE\s+""" + Regex.escape(bufferName) +
                """\b[^\n]*?SERIALIZE-NAME\s+"([^"]+)""""
        )
        return regex.find(content)?.groupValues?.get(1)
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
