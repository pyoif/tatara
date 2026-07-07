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
import java.io.OutputStream

/**
 * Prepends ABL package prefixes to CLASS/INTERFACE declarations,
 * and extracts bundled [Tatara.Api] runtime sources from the plugin
 * classpath into the output directory.
 *
 * Uses [InputChanges] so only changed/added/removed files are processed.
 */
@DisableCachingByDefault(because = "Writes files to output directory, incremental via InputChanges")
abstract class PrependPackageTask : DefaultTask() {

    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private val declLineRegex = Regex("""^\s*(CLASS|INTERFACE)\s+([\w.]+)""")

    @TaskAction
    fun prepend(inputChanges: InputChanges) {
        val root = srcDir.get().asFile
        val outRoot = outputDir.get().asFile
        var filesChanged = 0

        // Extract bundled Tatara.Api runtime classes (idempotent — overwrites each run
        // so updates to the plugin jars are picked up automatically).
        extractBundledApi(outRoot)

        inputChanges.getFileChanges(srcDir).forEach { change ->
            if (change.fileType == FileType.DIRECTORY) return@forEach

            val relative = change.file.relativeTo(root).invariantSeparatorsPath
            val outFile = File(outRoot, relative)

            when (change.changeType) {
                ChangeType.REMOVED -> {
                    if (outFile.delete()) {
                        logger.lifecycle("  removed: $relative")
                    }
                }
                ChangeType.ADDED, ChangeType.MODIFIED -> {
                    outFile.parentFile.mkdirs()
                    val prefix = computePrefix(change.file, root)
                    val source = change.file.readText()

                    var newContent: String
                    if (prefix.isEmpty()) {
                        newContent = source
                    } else {
                        newContent = prependDeclarations(source, prefix)
                    }

                    // Avoid touching the output file if content is unchanged
                    // (preserves file timestamps for downstream incremental tasks).
                    if (newContent == source) {
                        // Source unchanged; only copy if outFile missing or different
                        val existing = if (outFile.exists()) outFile.readText() else null
                        if (existing != source) {
                            outFile.writeText(source)
                            filesChanged++
                        }
                    } else {
                        val existing = if (outFile.exists()) outFile.readText() else null
                        if (existing != newContent) {
                            outFile.writeText(newContent)
                            filesChanged++
                        }
                    }
                }
            }
        }

        if (filesChanged > 0) {
            logger.lifecycle("PrependPackageTask: $filesChanged file(s) updated")
        }
    }

    /**
     * Performs a single-pass replacement on the first CLASS/INTERFACE line
     * that does not already carry the prefix.  Scans byte-by-byte instead of
     * splitting the source into lines, so large files cost less.
     */
    private fun prependDeclarations(source: String, prefix: String): String {
        var i = 0
        val len = source.length

        while (i < len) {
            val ch = source[i]
            when {
                ch == '\n' || ch == '\r' -> {
                    i++
                    continue
                }
                ch.isWhitespace() -> {
                    i++
                    continue
                }
                source.regionMatches(i, "CLASS", 0, 5, ignoreCase = true)
                    || source.regionMatches(i, "INTERFACE", 0, 9, ignoreCase = true) -> {

                    val keywordEnd = if (source.regionMatches(i, "INTERFACE", 0, 9, ignoreCase = true)) i + 9 else i + 5
                    // Skip whitespace after keyword
                    var j = keywordEnd
                    while (j < len && source[j].isWhitespace()) j++
                    // Read the class / interface name
                    val nameStart = j
                    while (j < len && (source[j].isJavaIdentifierPart() || source[j] == '.')) j++
                    val name = source.substring(nameStart, j)

                    if (name.startsWith(prefix)) return source // already prefixed

                    val indent = source.substring(0, i)
                    val rest = source.substring(j)
                    return indent + source.substring(i, keywordEnd) + " $prefix.$name" + rest
                }
                else -> {
                    // Hit a non-whitespace token that isn't CLASS/INTERFACE — skip to next line
                    while (i < len && source[i] != '\n' && source[i] != '\r') i++
                }
            }
        }
        return source // no CLASS/INTERFACE found
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
