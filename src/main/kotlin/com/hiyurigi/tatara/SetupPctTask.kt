package com.hiyurigi.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads latest PCT.jar from Riverside-Software GitHub Releases.
 * Caches globally at `~/.gradle/caches/tatara/pct/PCT.jar`.
 * Skipped when file already cached.
 */
@UntrackedTask(because = "Downloads to shared Gradle cache outside project directory")
abstract class SetupPctTask : DefaultTask() {

    @get:Internal
    val pctJar: File = File(cacheDir, "PCT.jar")

    init {
        onlyIf {
            !pctJar.exists() || pctJar.length() == 0L
        }
        outputs.upToDateWhen { pctJar.exists() && pctJar.length() > 0 }
    }

    @TaskAction
    fun download() {
        pctJar.parentFile.mkdirs()
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

        FileOutputStream(pctJar).use { out -> conn.inputStream.copyTo(out) }
        conn.disconnect()
        logger.lifecycle("PCT downloaded to ${pctJar.absolutePath}")
    }

    companion object {
        val cacheDir: File by lazy {
            val home = System.getenv("GRADLE_USER_HOME")
                ?: "${System.getProperty("user.home")}/.gradle"
            File(home, "caches/tatara/pct")
        }
    }
}
