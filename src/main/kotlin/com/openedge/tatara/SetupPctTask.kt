package com.openedge.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads latest PCT.jar from Riverside-Software GitHub Releases.
 * Caches at `~/.gradle/caches/tatara/pct/PCT.jar`.
 */
@DisableCachingByDefault(because = "Outputs to global cache outside build directory")
abstract class SetupPctTask : DefaultTask() {

    @get:OutputFile
    val pctJar: File
        get() = File(getCacheDir(), "PCT.jar")

    @TaskAction
    fun download() {
        val dest = pctJar
        if (dest.exists() && dest.length() > 0) {
            logger.lifecycle("PCT already cached: ${dest.absolutePath}")
            return
        }

        dest.parentFile.mkdirs()
        logger.lifecycle("Downloading latest PCT from Riverside-Software...")

        val url = "https://github.com/Riverside-Software/pct/releases/latest/download/PCT.jar"
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
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

    companion object {
        fun getCacheDir(): File {
            val home = System.getenv("GRADLE_USER_HOME")
                ?: "${System.getProperty("user.home")}/.gradle"
            return File(home, "tatara/pct")
        }
    }
}
