package com.pyoif.tatara

import java.io.File

object DtoParser {

    data class DtoProperty(
        val name: String,
        val ablType: String,
        val isRequired: Boolean = false
    )

    data class DtoInfo(
        val pathProperties: List<DtoProperty> = emptyList(),
        val queryProperties: List<DtoProperty> = emptyList(),
        val bodyProperties: List<DtoProperty> = emptyList()
    ) {
        companion object {
            val EMPTY = DtoInfo()
        }
    }

    private val propDefRegex = Regex(
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)"""
    )
    private val requiredRegex = Regex("""(?i)//\s*@Required""")
    private val sectionRegex = Regex(
        """(?i)CLASS\s+(PathSection|QuerySection|BodySection)\s*[:\s](.*?)END\s+CLASS\.""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(dtoClassName: String, srcRoot: File): DtoInfo {
        val file = resolveFile(dtoClassName, srcRoot) ?: return DtoInfo.EMPTY
        val content = file.readText()
        val pathProps = mutableListOf<DtoProperty>()
        val queryProps = mutableListOf<DtoProperty>()
        val bodyProps = mutableListOf<DtoProperty>()

        sectionRegex.findAll(content).forEach { sectionMatch ->
            val sectionName = sectionMatch.groupValues[1]
            val sectionBody = sectionMatch.groupValues[2]
            val targetList = when (sectionName) {
                "PathSection" -> pathProps
                "QuerySection" -> queryProps
                "BodySection" -> bodyProps
                else -> return@forEach
            }
            parseSection(sectionBody, targetList)
        }
        return DtoInfo(pathProps, queryProps, bodyProps)
    }

    private fun resolveFile(dtoClassName: String, srcRoot: File): File? {
        val relativePath = dtoClassName.replace(".", "/") + ".cls"
        val file = File(srcRoot, relativePath)
        return if (file.exists()) file else null
    }

    private fun parseSection(sectionBody: String, targetList: MutableList<DtoProperty>) {
        val lines = sectionBody.lines()
        var previousLineHasRequired = false
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (requiredRegex.containsMatchIn(line)) {
                previousLineHasRequired = true
                continue
            }
            propDefRegex.find(line)?.let { m ->
                targetList.add(DtoProperty(
                    m.groupValues[1],
                    m.groupValues[2],
                    previousLineHasRequired || requiredRegex.containsMatchIn(line)
                ))
                previousLineHasRequired = false
            }
        }
    }
}
