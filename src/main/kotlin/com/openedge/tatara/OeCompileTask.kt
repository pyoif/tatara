package com.openedge.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.kotlin.dsl.withGroovyBuilder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Compiles ABL source to r-code using PCT (Progress Compiler Toolkit).
 *
 * Downloads latest PCT.jar from Riverside-Software GitHub Releases on first run
 * and caches it at `~/.gradle/caches/tatara/pct/PCT.jar`.
 */
@DisableCachingByDefault(because = "Calls native AVM binary via ANT; not cacheable")
abstract class OeCompileTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val generatedDir: DirectoryProperty

    @get:OutputDirectory
    abstract val rcodeDir: DirectoryProperty

    @get:Input
    abstract val dlcHome: Property<String>

    @get:Input
    abstract val paramFile: Property<String>

    companion object {
        private const val PCT_DOWNLOAD_URL =
            "https://github.com/Riverside-Software/pct/releases/latest/download/PCT.jar"
    }

    @TaskAction
    fun compile() {
        val outDir = rcodeDir.get().asFile
        outDir.mkdirs()

        val srcPath = srcDir.get().asFile.absolutePath
        val genPath = generatedDir.get().asFile.absolutePath
        val dlc = dlcHome.get()

        val pctJar = resolvePct()

        ant.withGroovyBuilder {
            "taskdef"("resource" to "PCT.properties", "classpath" to pctJar, "loaderRef" to "pct")
            "typedef"("resource" to "types.properties", "classpath" to pctJar, "loaderRef" to "pct")

            "PCTCompile"(
                "destDir" to outDir.absolutePath,
                "dlcHome" to dlc,
                "paramFile" to paramFile.get()
            ) {
                "propath" {
                    "pathelement"("location" to srcPath)
                    "pathelement"("location" to genPath)
                    "pathelement"("location" to "$dlc/tty/netlib/OpenEdge.Net.pl")
                    "pathelement"("location" to "$dlc/tty/OpenEdge.Core.pl")
                    "pathelement"("location" to "$dlc/tty")
                }

                "fileset"("dir" to srcPath) {
                    "include"("name" to "**/*.p")
                    "include"("name" to "**/*.cls")
                    "include"("name" to "**/*.w")
                }

                "fileset"("dir" to genPath) {
                    "include"("name" to "**/*.cls")
                }
            }
        }
    }

    // ---- PCT download & cache ----

    private fun resolvePct(): String {
        val cacheDir = File(getGradleCacheHome(), "tatara/pct")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "PCT.jar")

        // Check if file exists and is valid (non-empty jar)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            logger.lifecycle("Using cached PCT: ${cacheFile.absolutePath}")
            return cacheFile.absolutePath
        }

        logger.lifecycle("Downloading latest PCT from Riverside-Software...")
        downloadFile(PCT_DOWNLOAD_URL, cacheFile)
        logger.lifecycle("PCT downloaded to ${cacheFile.absolutePath}")

        return cacheFile.absolutePath
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true
        conn.connect()

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw GradleException("Failed to download PCT: HTTP ${conn.responseCode} from $urlStr")
        }

        dest.parentFile.mkdirs()
        FileOutputStream(dest).use { out -> conn.inputStream.copyTo(out) }
        conn.disconnect()
    }

    private fun getGradleCacheHome(): File {
        val env = System.getenv("GRADLE_USER_HOME")
        return if (env != null) File(env) else File(System.getProperty("user.home"), ".gradle")
    }
}
