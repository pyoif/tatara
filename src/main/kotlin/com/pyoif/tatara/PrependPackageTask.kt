package com.pyoif.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.InputChanges
import org.gradle.work.Incremental
import java.io.File

/**
 * Prepends ABL package prefixes to CLASS/INTERFACE declarations by reading
 * only the file header until the first declaration is found (or END OF FILE).
 *
 * Also extracts bundled [Tatara.Api] runtime sources into [outputDir].
 */
@DisableCachingByDefault(because = "Writes files to output directory, incremental via InputChanges")
abstract class PrependPackageTask : DefaultTask() {

    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private val declRegex = Regex("""^\s*(CLASS|INTERFACE)\s+([\w.]+)""")

    @TaskAction
    fun prepend(inputChanges: InputChanges) {
        val root = srcDir.get().asFile
        val outRoot = outputDir.get().asFile
        var filesChanged = 0

        extractBundledApi(outRoot)

        inputChanges.getFileChanges(srcDir).forEach { change ->
            if (change.fileType == FileType.DIRECTORY) return@forEach

            val relative = change.file.relativeTo(root).invariantSeparatorsPath
            val outFile = File(outRoot, relative)

            when (change.changeType) {
                ChangeType.REMOVED -> {
                    if (outFile.delete()) logger.lifecycle("  removed: $relative")
                }
                ChangeType.ADDED, ChangeType.MODIFIED -> {
                    outFile.parentFile.mkdirs()
                    val prefix = computePrefix(change.file, root)

                    val result = prependPrefix(change.file, prefix)
                    val existing = if (outFile.exists()) outFile.readText() else null
                    if (result != existing) {
                        outFile.writeText(result)
                        filesChanged++
                    }
                }
            }
        }

        if (filesChanged > 0) {
            logger.lifecycle("PrependPackageTask: $filesChanged file(s) updated")
        }
    }

    /**
     * Reads [sourceFile] line-by-line, stops as soon as the CLASS/INTERFACE
     * declaration is found (or rewritten with prefix).  Never reads past that
     * point; for deep files the cost is O(distance-to-declaration) instead of
     * O(file-size).
     */
    private fun prependPrefix(sourceFile: File, prefix: String): String {
        val buf = sourceFile.bufferedReader()
        val out = StringBuilder()

        try {
            var done = false
            buf.forEachLine { line ->
                if (!done) {
                    val match = declRegex.find(line)
                    if (match != null) {
                        val keyword = match.groupValues[1]
                        val name = match.groupValues[2]
                        if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                            val indent = line.substring(0, match.range.first)
                            val rest = line.substring(match.range.last + 1)
                            out.append(indent).append(keyword)
                                .append(" ").append(prefix).append(".").append(name)
                                .append(rest).append("\r\n")
                        } else {
                            out.append(line).append("\r\n")
                        }
                        done = true  // short-circuit: CLASS/INTERFACE found, stream rest raw
                    } else if (line.isBlank() || line.trimStart().startsWith("/*") || line.trimStart().startsWith("//")) {
                        // Comments, blank lines, USING statements in header — pass through
                        out.append(line).append("\r\n")
                    } else {
                        // Non-comment non-blank non-CLASS/INTERFACE line before declaration
                        // (could be annotations, USING, etc.) — still in header zone
                        out.append(line).append("\r\n")
                        // Only short-circuit on CLASS/INTERFACE or true "body" start.
                        // Heuristic: if this line starts with a keyword like METHOD, DEFINE, etc.
                        // we treat it as "no CLASS found" and stop searching.
                        val trimmed = line.trimStart().take(8).uppercase()
                        when {
                            trimmed.startsWith("METHOD") ||
                            trimmed.startsWith("DEFINE") ||
                            trimmed.startsWith("CONSTRUC") ||
                            trimmed.startsWith("DESTRUCT") ||
                            trimmed.startsWith("FUNCTION") ||
                            trimmed.startsWith("PROCEDUR") -> done = true
                        }
                    }
                } else {
                    // Past declaration — stream remainder unchanged
                    out.append(line).append("\r\n")
                }
            }
        } finally {
            buf.close()
        }

        return out.toString()
    }

    private fun computePrefix(file: File, root: File): String {
        val parent = file.parentFile ?: return ""
        if (parent == root) return ""
        val relative = parent.relativeTo(root).invariantSeparatorsPath
        if (relative.isEmpty()) return ""
        return relative.replace("/", ".")
    }

    // ---- Tatara.Api extraction ----

    private fun extractBundledApi(outRoot: File) {
        val resourceDir = "Tatara/Api"
        val resourceUrl = javaClass.classLoader.getResource(resourceDir) ?: return
        val uri = resourceUrl.toURI()
        val apiOut = File(outRoot, resourceDir)

        if (uri.scheme == "jar") {
            java.nio.file.FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs ->
                val root = fs.getPath("/$resourceDir")
                java.nio.file.Files.walk(root)
                    .filter { java.nio.file.Files.isRegularFile(it) }
                    .forEach { source ->
                        val relPath = root.relativize(source).toString().replace("\\", "/")
                        val targetFile = File(apiOut, relPath)
                        targetFile.parentFile.mkdirs()
                        val inputUrl = javaClass.classLoader.getResource("$resourceDir/$relPath")
                        inputUrl?.openStream()?.use { input ->
                            targetFile.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
            }
        } else {
            val root = java.io.File(uri)
            root.walkTopDown().filter { it.isFile }.forEach { source ->
                val rel = source.relativeTo(root)
                val target = File(apiOut, rel.path)
                target.parentFile.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
    }
}
