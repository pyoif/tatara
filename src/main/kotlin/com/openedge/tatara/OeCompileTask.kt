package com.openedge.tatara

import com.progress.gradle.abl.config.DbConnectionOptions
import com.progress.gradle.abl.tasks.ABLCompile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Compiles ABL source to r-code using OpenEdge DevOps Framework (OEDF).
 *
 * Extends OEDF's native [ABLCompile] task. Input properties are
 * declared as abstract Gradle properties for consumer wiring;
 * the overridden [compile] method resolves them lazily and
 * delegates to the OEDF compiler.
 */
@DisableCachingByDefault(because = "Calls OpenEdge AVM binary; not cacheable across machines")
abstract class OeCompileTask : ABLCompile() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val generatedDir: DirectoryProperty

    @get:Input
    abstract val dlcHome: Property<String>

    @get:Input
    abstract val paramFile: Property<String>

    override fun compile(): Any? {
        val dlc = dlcHome.get()
        val pfPath = paramFile.get()

        // 1. DLC home
        setDlcHome(dlc)

        // 2. Propath: source dirs + OE framework libraries
        propath(
            srcDir.get().asFile.absolutePath,
            generatedDir.get().asFile.absolutePath,
            "$dlc/tty/netlib/OpenEdge.Net.pl",
            "$dlc/tty/OpenEdge.Core.pl",
            "$dlc/tty"
        )

        // 3. Source files (SourceTask API)
        // include patterns apply to all source roots; generatedDir only has .cls
        // so **/*.p and **/*.w won't match anything there. Result is equivalent
        // to the old per-directory fileset approach.
        setSource(listOf(srcDir.get().asFile, generatedDir.get().asFile))
        include("**/*.p", "**/*.cls", "**/*.w")

        // 4. Database connections
        val pfFile = File(pfPath)
        if (pfFile.exists()) {
            val dbs = parsePfFile(pfFile)
            if (dbs.isNotEmpty()) {
                dbs.forEach { db ->
                    val conn = DbConnectionOptions()
                    conn.parameterFile = pfFile.absolutePath
                    conn.dbName = db["name"] ?: ""
                    conn.host = db["host"] ?: ""
                    conn.port = db["port"] ?: ""
                    conn.username = db["user"] ?: ""
                    conn.password = db["pass"] ?: ""
                    dbConnections.add(conn)
                }
            } else {
                val conn = DbConnectionOptions()
                conn.parameterFile = pfFile.absolutePath
                dbConnections.add(conn)
            }
        }

        // 5. Delegate to OEDF compiler
        return super.compile()
    }

    private fun parsePfFile(pfFile: File): List<Map<String, String>> {
        val connections = mutableListOf<Map<String, String>>()
        val tokens = pfFile.readText().split(Regex("\\s+")).filter { it.isNotBlank() }
        var i = 0
        var current = mutableMapOf<String, String>()

        fun flush() {
            if (current.containsKey("name")) {
                connections.add(current.toMap())
            }
            current = mutableMapOf()
        }

        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "-db" && i + 1 < tokens.size -> {
                    flush()
                    current["name"] = tokens[++i]
                }
                token == "-d" && i + 1 < tokens.size -> i++
                token == "-H" && i + 1 < tokens.size -> current["host"] = tokens[++i]
                token == "-S" && i + 1 < tokens.size -> current["port"] = tokens[++i]
                token == "-N" && i + 1 < tokens.size -> current["transport"] = tokens[++i]
                token == "-U" && i + 1 < tokens.size -> current["user"] = tokens[++i]
                token == "-P" && i + 1 < tokens.size -> current["pass"] = tokens[++i]
            }
            i++
        }
        flush()
        return connections
    }
}
