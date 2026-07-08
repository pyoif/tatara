package com.pyoif.tatara

import java.io.File

data class RouteDef(
    val routePath: String,
    val httpMethod: String,
    val className: String,
    val ablMethod: String,
    val pathParams: List<String> = emptyList(),
    val requestDtoClassName: String? = null,
    val responseDtoClassName: String? = null,
    val errorResponses: Map<Int, String> = emptyMap()
)

class RouteParser {
    private val httpVerbRegex = Regex(
        """(?i)@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\(\s*(["'])([^"']+)\2\s*\)"""
    )
    private val classRegex = Regex("""CLASS\s+([\w.]+)""")
    private val methodDefRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(?:\w+\s+)?(\w+)\s*[(]"""
    )
    private val pathParamRegex = Regex("""\{(\w+)\}""")
    private val responseAnnotationRegex = Regex(
        """@Response\(\s*(\d{3})\s*,\s*([\w.]+)\s*\)"""
    )
    private val methodSigRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(\w+(?:\.\w+)*)\s+(\w+)\s*\(\s*(?:INPUT\s+\w+\s+AS\s+([\w.]+)\s*)?\)\s*:"""
    )

    fun extractRoutesFromFile(file: File): List<RouteDef> {
        val classMatch = classRegex.find(file.readText()) ?: return emptyList()
        val className = classMatch.groupValues[1]

        val results = mutableListOf<RouteDef>()
        var pendingVerb: String? = null
        var pendingPath: String? = null
        var pendingPathParams: List<String> = emptyList()
        var pendingErrorResponses = mutableMapOf<Int, String>()

        file.forEachLine { line ->
            responseAnnotationRegex.find(line)?.let { m ->
                pendingErrorResponses[m.groupValues[1].toInt()] = m.groupValues[2]
            }
            httpVerbRegex.find(line)?.let { m ->
                pendingVerb = m.groupValues[1].uppercase()
                pendingPath = m.groupValues[3].let { if (it.startsWith("/")) it.substring(1) else it }
                pendingPathParams = pathParamRegex.findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
            }
            methodSigRegex.find(line)?.let { m ->
                if (pendingVerb != null && pendingPath != null) {
                    val returnTypeRaw = m.groupValues[1]
                    val methodName = m.groupValues[2]
                    val inputType = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }

                    val reqDto = if (inputType != null && inputType != "Tatara.Api.RequestContext") inputType else null
                    val respDto = if (returnTypeRaw.uppercase() == "VOID") null else returnTypeRaw

                    if (results.none { it.routePath == pendingPath && it.httpMethod == pendingVerb }) {
                        results.add(RouteDef(
                            routePath = pendingPath!!,
                            httpMethod = pendingVerb!!,
                            className = className,
                            ablMethod = methodName,
                            pathParams = pendingPathParams,
                            requestDtoClassName = reqDto,
                            responseDtoClassName = respDto,
                            errorResponses = pendingErrorResponses.toMap()
                        ))
                    }
                }
                pendingVerb = null; pendingPath = null
                pendingPathParams = emptyList(); pendingErrorResponses = mutableMapOf()
            }
            methodDefRegex.find(line)?.let { m ->
                if (pendingVerb != null && pendingPath != null) {
                    val ablMethod = m.groupValues[1]
                    if (results.none { it.routePath == pendingPath && it.httpMethod == pendingVerb }) {
                        results.add(RouteDef(pendingPath!!, pendingVerb!!, className, ablMethod, pendingPathParams,
                            errorResponses = pendingErrorResponses.toMap()))
                    }
                }
                pendingVerb = null; pendingPath = null
                pendingPathParams = emptyList(); pendingErrorResponses = mutableMapOf()
            }
        }
        return results
    }

    fun getPathParams(path: String): List<String> {
        return pathParamRegex.findAll(path).map { it.groupValues[1] }.toList()
    }
}
