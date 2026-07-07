package com.pyoif.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.streams.toList

@DisableCachingByDefault(because = "Produces platform-specific WAR artifact, not suitable for build cache")
abstract class PackageWarTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val rcodeDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val handlersDir: DirectoryProperty

    @get:OutputFile
    abstract val warFile: RegularFileProperty

    @TaskAction
    fun packageWar() {
        val rcodeRoot = rcodeDir.get().asFile
        val warPath = warFile.get().asFile
        warPath.parentFile.mkdirs()

        ZipOutputStream(BufferedOutputStream(warPath.outputStream())).use { zip ->
            
            // 1. Package baseline web configurations from buildSrc resources
            packageClasspathResources(zip)

            // 2. Package compiled ABL r-code under WEB-INF/openedge/
            val rFiles = rcodeRoot.walkTopDown().filter { it.isFile && it.extension == "r" }.toList()
            logger.lifecycle("Packaging ${rFiles.size} r-code file(s) into PASOE WAR: ${warPath.name}")

            rFiles.forEach { file ->
                val relativePath = file.relativeTo(rcodeRoot).invariantSeparatorsPath
                val targetEntryPath = "WEB-INF/openedge/$relativePath"
                addFileToZip(zip, file, targetEntryPath)
            }

            // 3. Bundle .handlers files: <serviceName>.handlers → WEB-INF/adapters/web/<serviceName>/<serviceName>.handlers
            val handlersRoot = handlersDir.get().asFile
            if (handlersRoot.exists()) {
                val handlersFiles = handlersRoot.walkTopDown().filter { it.isFile && it.extension == "handlers" }.toList()
                logger.lifecycle("Packaging ${handlersFiles.size} handlers file(s)")
                handlersFiles.forEach { file ->
                    val serviceName = file.nameWithoutExtension
                    val entryName = "WEB-INF/adapters/web/$serviceName/$serviceName.handlers"
                    addFileToZip(zip, file, entryName)
                }
            }
        }

        logger.lifecycle("PASOE WAR successfully written to: ${warPath.absolutePath}")
    }

    private fun packageClasspathResources(zip: ZipOutputStream) {
        // Resolve the internal template root directory from buildSrc resources
        val resourceName = "pasoeTemplate"
        val resourceUrl = javaClass.classLoader.getResource(resourceName) 
            ?: throw IllegalStateException("Required baseline 'pasoeTemplate' not found in buildSrc resources!")

        val uri = resourceUrl.toURI()
        
        if (uri.scheme == "jar") {
            // Handle scenario when buildSrc is packaged as a jar file
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs ->
                val templateRoot = fs.getPath("/$resourceName")
                writePathsToZip(templateRoot, zip)
            }
        } else {
            // Handle local file system execution (standard Gradle execution)
            val templateRoot = Path.of(uri)
            writePathsToZip(templateRoot, zip)
        }
    }

    private fun writePathsToZip(root: Path, zip: ZipOutputStream) {
        Files.walk(root)
            .filter { Files.isRegularFile(it) }
            .forEach { path ->
                // Extract relative path to maintain WEB-INF/... structure inside the WAR
                val relativePath = root.relativize(path).toString().replace("\\", "/")
                zip.putNextEntry(ZipEntry(relativePath))
                Files.copy(path, zip)
                zip.closeEntry()
            }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryPath: String) {
        zip.putNextEntry(ZipEntry(entryPath))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
