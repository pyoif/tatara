package com.openedge.tatara

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
 * Scans `.cls` source files under [srcDir], computes the ABL package prefix from each
 * file's directory path relative to [srcDir], then generates a copy under [outputDir]
 * with the prefix prepended to `CLASS` and `INTERFACE` declarations that don't already
 * have it.
 *
 * Uses [InputChanges] so only changed/added/removed files are processed —
 * unchanged files are left untouched.
 */
@DisableCachingByDefault(because = "Writes files to output directory, incremental via InputChanges")
abstract class PrependPackageTask : DefaultTask() {

    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private val declRegex = Regex("""^\s*(CLASS|INTERFACE)\s+([\w.]+).*""")

    @TaskAction
    fun prepend(inputChanges: InputChanges) {
        val root = srcDir.get().asFile
        val outRoot = outputDir.get().asFile
        var filesChanged = 0

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
                    val content = change.file.readText()
                    val newContent = prependDeclarations(content, change.file, root)

                    val existing = if (outFile.exists()) outFile.readText() else null
                    if (newContent != existing) {
                        outFile.writeText(newContent)
                        filesChanged++
                    }
                }
            }
        }

        if (filesChanged > 0) {
            logger.lifecycle("PrependPackageTask: $filesChanged file(s) updated with prefix")
        }
    }

    private fun prependDeclarations(content: String, file: File, root: File): String {
        val prefix = computePrefix(file, root)
        if (prefix.isEmpty()) return content

        val lines = content.lines().toMutableList()
        for (i in lines.indices) {
            val match = declRegex.find(lines[i])
            if (match != null) {
                val keyword = match.groupValues[1]
                val currentName = match.groupValues[2]
                if (currentName.startsWith(prefix)) return content

                val indent = lines[i].substring(0, match.range.first)
                val rest = match.value.substringAfter(currentName)
                lines[i] = indent + keyword + " $prefix.$currentName" + rest
                return lines.joinToString("\r\n")
            }
        }
        return content
    }

    private fun computePrefix(file: File, root: File): String {
        val parent = file.parentFile ?: return ""
        if (parent == root) return ""
        val relative = parent.relativeTo(root).invariantSeparatorsPath
        if (relative.isEmpty()) return ""
        return relative.replace("/", ".")
    }
}
