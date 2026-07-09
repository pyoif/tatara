package com.pyoif.tatara

import java.io.File

object DtoParser {
    enum class ParamLocation { PATH, QUERY, BODY, UNKNOWN }

    data class DtoProperty(
        val name: String,
        val ablType: String,
        val isRequired: Boolean = false,
        val location: ParamLocation = ParamLocation.UNKNOWN,
        val isExtent: Boolean = false
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
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body)""")

    fun parse(dtoClassName: String, srcRoot: File): DtoInfo {
        val file = resolveFile(dtoClassName, srcRoot) ?: return DtoInfo.EMPTY
        val properties = mutableListOf<DtoProperty>()
        val lines = file.readText().lines()

        var isReq = false
        var loc = ParamLocation.UNKNOWN

        for (line in lines) {
            val trimmed = line.trim()
            annotationRegex.findAll(trimmed).forEach { m ->
                when (m.groupValues[1].lowercase()) {
                    "required" -> isReq = true
                    "path" -> { loc = ParamLocation.PATH; isReq = false }
                    "query" -> { loc = ParamLocation.QUERY; isReq = false }
                    "body" -> { loc = ParamLocation.BODY; isReq = false }
                }
            }

            propDefRegex.find(trimmed)?.let { m ->
                properties.add(DtoProperty(
                    name = m.groupValues[1],
                    ablType = m.groupValues[2],
                    isRequired = isReq,
                    location = loc,
                    isExtent = m.groups[3]?.value != null
                ))
                // Reset @Required only — location sticks until next @Path/@Query/@Body
                isReq = false
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