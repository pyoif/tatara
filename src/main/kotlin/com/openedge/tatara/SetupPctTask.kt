package com.openedge.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads latest PCT.jar from Riverside-Software GitHub Releases.
 * Caches at `~/.gradle/caches/tatara/pct/PCT.jar`.
 */
@CacheableTask
abstract class SetupPctTask : DefaultTask() {

    @get:Input
    val downloadUrl: String =
        "https://github.com/Riverside-Software/pct/releases/latest/download/PCT.jar"

    @get:OutputFile
    val pctJar: File
        get() = project.layout.buildDirectory.dir("tatara/pct").get().asFile.resolve("PCT.jar")

    @TaskAction
    fun download() {
        val dest = pctJar
        dest.parentFile.mkdirs()

        if (dest.exists() && dest.length() > 0) {
            logger.lifecycle("PCT already cached: ${dest.absolutePath}")
            return
        }

        logger.lifecycle("Downloading latest PCT from Riverside-Software...")
        val conn = URI(downloadUrl).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true
        conn.connect()

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw GradleException("Failed to download PCT: HTTP ${conn.responseCode}")
        }

        FileOutputStream(dest).use { out -> conn.inputStream.copyTo(out) }
        conn.disconnect()
        logger.lifecycle("PCT downloaded to ${dest.absolutePath}")
    }
}
