package com.hiyurigi.tatara

import org.gradle.api.DefaultTask
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

/**
 * Compiles ABL source to r-code using PCT (Progress Compiler Toolkit).
 *
 * Requires [pctJarPath] pointing to a valid PCT.jar.
 * The plugin's [SetupPctTask] auto-downloads and wires this.
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

    @get:Input
    abstract val pctJarPath: Property<String>

    @TaskAction
    fun compile() {
        val outDir = rcodeDir.get().asFile
        outDir.mkdirs()

        val srcPath = srcDir.get().asFile.absolutePath
        val genPath = generatedDir.get().asFile.absolutePath
        val dlc = dlcHome.get()
        val pctJar = pctJarPath.get()

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
}
