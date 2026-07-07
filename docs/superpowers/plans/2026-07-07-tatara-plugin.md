# Tatara Gradle Plugin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract all buildSrc custom Gradle tasks into a standalone plugin (`tatara`) in `D:/project/tatara`, converting `PctCompileTask` to `OeCompileTask` backed by OEDF's `progress.openedge.abl-base` plugin, then consume it from `backend`.

**Architecture:** The `tatara` plugin bundles 4 task types — `PrependPackageTask`, `GenerateRouteTask`, `OeCompileTask`, `PackageWarTask` — plus their classpath resources. It declares `progress.openedge.abl-base:2.4.0` as dependency. `backend` consumes `tatara` via `includeBuild`, registers tasks, and drops its entire `buildSrc` directory.

**Tech Stack:** Gradle 9.x, Kotlin DSL, OpenEdge 12.3, progress.openedge.abl-base 2.4.0, Java 8+ (OE compat)

## Global Constraints

- OE 12.3 DLC at `C:/PROGRESS/OpenEdge12` (from `gradle.properties`)
- OEDF plugin: `progress.openedge.abl-base` v2.4.0
- Consumed via `includeBuild("D:/project/tatara")` from `backend/settings.gradle.kts`
- Java source/target compatibility: Java 8 (OE tooling requirement)
- Plugin published to `mavenLocal()` for portability; local dev uses included build
- Database connectivity required at compile time (`config/database.pf`)

---

## File Structure

**Create — `D:/project/tatara/`:**
```
tatara/
├── settings.gradle.kts
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/openedge/tatara/
    │   ├── TataraPlugin.kt              ← Gradle plugin entry point
    │   ├── OeCompileTask.kt             ← replaces PctCompileTask, extends OEDF base
    │   ├── GenerateRouteTask.kt         ← copy from buildSrc
    │   ├── PrependPackageTask.kt        ← copy from buildSrc
    │   └── PackageWarTask.kt            ← copy from buildSrc
    └── resources/
        ├── META-INF/gradle-plugins/
        │   └── com.openedge.tatara.properties
        ├── RouteShimTemplate.cls        ← copy from buildSrc resources
        └── pasoeTemplate/               ← copy tree from buildSrc resources
```

**Modify — `D:/ProjectWeb/backend/`:**
```
backend/
├── settings.gradle.kts                  ← add includeBuild
├── build.gradle.kts                     ← apply tatara plugin, rewire tasks
└── buildSrc/                            ← DELETE entirely
```

---

### Task 0: Verify preconditions

**Files:**
- Check: `C:/PROGRESS/OpenEdge12/version` exists
- Check: `D:/ProjectWeb/backend/buildSrc/src/main/kotlin/PctCompileTask.kt` exists
- Check: `D:/ProjectWeb/backend/buildSrc/src/main/resources/RouteShimTemplate.cls` exists
- Check: `D:/ProjectWeb/backend/buildSrc/src/main/resources/pasoeTemplate/` exists

- [ ] **Step 1: Run verification commands**

```bash
ls "C:/PROGRESS/OpenEdge12/version" && cat "C:/PROGRESS/OpenEdge12/version"
ls "D:/ProjectWeb/backend/buildSrc/src/main/kotlin/PctCompileTask.kt"
ls "D:/ProjectWeb/backend/buildSrc/src/main/resources/RouteShimTemplate.cls"
ls "D:/ProjectWeb/backend/buildSrc/src/main/resources/pasoeTemplate/"
```

Expected: All paths exist, version shows "OpenEdge Release 12.3".

---

### Task 1: Create tatara project skeleton

**Files:**
- Create: `D:/project/tatara/settings.gradle.kts`
- Create: `D:/project/tatara/build.gradle.kts`
- Create: `D:/project/tatara/src/main/kotlin/com/openedge/tatara/TataraPlugin.kt`
- Create: `D:/project/tatara/src/main/resources/META-INF/gradle-plugins/com.openedge.tatara.properties`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "tatara"
```

- [ ] **Step 2: Create `build.gradle.kts`**

`.gradle.kts` at root of plugin project:

```kotlin
plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.openedge"
version = "1.0.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("progress.openedge.abl-base:progress.openedge.abl-base.gradle.plugin:2.4.0")
}

gradlePlugin {
    plugins {
        register("tatara") {
            id = "com.openedge.tatara"
            implementationClass = "com.openedge.tatara.TataraPlugin"
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
```

- [ ] **Step 3: Create `com.openedge.tatara.properties`**

```properties
implementation-class=com.openedge.tatara.TataraPlugin
```

- [ ] **Step 4: Create `TataraPlugin.kt`**

```kotlin
package com.openedge.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Plugin is a task-type registry — no configuration logic here.
        // Task types become available to consumers via the plugin classpath.
    }
}
```

- [ ] **Step 5: Verify basic build**

```bash
cd D:/project/tatara && ./gradlew build
```

Expected: BUILD SUCCESSFUL. If `gradlew` doesn't exist, generate with `gradle wrapper` from any local Gradle.

- [ ] **Step 6: Commit**

```bash
cd D:/project/tatara && git add -A && git commit -m "init: tatara gradle plugin scaffold with OEDF dependency"
```

---

### Task 2: Copy PrependPackageTask into tatara

**Files:**
- Create: `D:/project/tatara/src/main/kotlin/com/openedge/tatara/PrependPackageTask.kt`

- [ ] **Step 1: Copy task file**

Copy `D:/ProjectWeb/backend/buildSrc/src/main/kotlin/PrependPackageTask.kt` to `D:/project/tatara/src/main/kotlin/com/openedge/tatara/PrependPackageTask.kt`. Change the package declaration from implicit (none) to:

```kotlin
package com.openedge.tatara
```

Everything else stays identical. Add the package line at the very top, before `import` statements.

- [ ] **Step 2: Verify build**

```bash
cd D:/project/tatara && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd D:/project/tatara && git add -A && git commit -m "feat: copy PrependPackageTask into tatara plugin"
```

---

### Task 3: Copy GenerateRouteTask into tatara

**Files:**
- Create: `D:/project/tatara/src/main/kotlin/com/openedge/tatara/GenerateRouteTask.kt`
- Create: `D:/project/tatara/src/main/resources/RouteShimTemplate.cls`

- [ ] **Step 1: Copy task file**

Copy `D:/ProjectWeb/backend/buildSrc/src/main/kotlin/GenerateRouteTask.kt` to `D:/project/tatara/src/main/kotlin/com/openedge/tatara/GenerateRouteTask.kt`. Add package declaration at top:

```kotlin
package com.openedge.tatara
```

This task loads `RouteShimTemplate.cls` via `javaClass.getResource("/RouteShimTemplate.cls")` — that path is absolute to classpath root and will work unchanged in the plugin jar.

- [ ] **Step 2: Copy template resource**

```bash
cp "D:/ProjectWeb/backend/buildSrc/src/main/resources/RouteShimTemplate.cls" "D:/project/tatara/src/main/resources/RouteShimTemplate.cls"
```

- [ ] **Step 3: Verify build**

```bash
cd D:/project/tatara && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd D:/project/tatara && git add -A && git commit -m "feat: copy GenerateRouteTask and RouteShimTemplate into tatara plugin"
```

---

### Task 4: Copy PackageWarTask into tatara

**Files:**
- Create: `D:/project/tatara/src/main/kotlin/com/openedge/tatara/PackageWarTask.kt`
- Copy: entire `pasoeTemplate/` directory tree from buildSrc resources

- [ ] **Step 1: Copy task file**

Copy `D:/ProjectWeb/backend/buildSrc/src/main/kotlin/PackageWarTask.kt` to `D:/project/tatara/src/main/kotlin/com/openedge/tatara/PackageWarTask.kt`. Add package declaration at top:

```kotlin
package com.openedge.tatara
```

This task loads `pasoeTemplate/` via `javaClass.classLoader.getResource("pasoeTemplate")` — absolute classpath path, works unchanged in plugin jar.

- [ ] **Step 2: Copy pasoeTemplate resources**

```bash
cp -r "D:/ProjectWeb/backend/buildSrc/src/main/resources/pasoeTemplate" "D:/project/tatara/src/main/resources/"
```

- [ ] **Step 3: Verify build**

```bash
cd D:/project/tatara && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd D:/project/tatara && git add -A && git commit -m "feat: copy PackageWarTask and pasoeTemplate into tatara plugin"
```

---

### Task 5: Create OeCompileTask — replace PctCompileTask with OEDF-backed compile

**Files:**
- Create: `D:/project/tatara/src/main/kotlin/com/openedge/tatara/OeCompileTask.kt`

**Discovery note:** The OEDF plugin's exact base class for ABL compilation will be confirmed during implementation. Based on OEDF 2.4.0 documentation and source patterns, the expected base class is `com.progress.openedge.abl.tasks.ABLCompileTask` or similar. The implementation step includes a jar inspection to confirm before writing.

- [ ] **Step 1: Discover OEDF base class**

```bash
cd D:/project/tatara && ./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep "openedge"
```

Then inspect the OEDF jar to find the ABL compilation task class:

```bash
find "$HOME/.gradle/caches" -name "progress.openedge.abl-base.gradle.plugin-2.4.0.jar" 2>/dev/null | head -1 | xargs jar tf 2>/dev/null | grep -i "compile\|abl" | grep -i "task\|class" | head -20
```

Identify the exact fully-qualified class name:
- Look for classes ending in `Task`, `Compile`, or `Abl` in `com.progress.openedge` package
- Expected candidate: `com.progress.openedge.abl.tasks.AblCompile` or `com.progress.openedge.tasks.ABLCompileTask`
- Also check which properties exist (destDir, dlcHome, srcDirs, propath, databaseConnections, etc.)
- If the OEDF jar is not in cache yet, run `./gradlew build` first to download it

- [ ] **Step 2: Write OeCompileTask**

Create `D:/project/tatara/src/main/kotlin/com/openedge/tatara/OeCompileTask.kt`:

```kotlin
package com.openedge.tatara

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
// DISCOVERY: replace with actual OEDF class import
import com.progress.openedge.abl.tasks.AblCompileTask
import java.io.File

abstract class OeCompileTask : AblCompileTask() {

    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:InputDirectory
    abstract val generatedDir: DirectoryProperty

    @get:OutputDirectory
    abstract val rcodeDir: DirectoryProperty

    @get:Input
    abstract val dlcHome: Property<String>

    @get:Input
    abstract val paramFile: Property<String>

    @TaskAction
    fun compile() {
        val outDir = rcodeDir.get().asFile
        outDir.mkdirs()

        val srcPath = srcDir.get().asFile.absolutePath
        val genPath = generatedDir.get().asFile.absolutePath
        val dlc = dlcHome.get()

        // Wire inherited OEDF properties
        destDir.set(outDir)
        dlcHome.set(dlc)
        databaseConnections.from(paramFile.map { pf ->
            parsePfFile(File(pf))
        })

        // Build propath
        val propath = listOf(
            File(srcPath),
            File(genPath),
            File("$dlc/tty/netlib/OpenEdge.Net.pl"),
            File("$dlc/tty/OpenEdge.Core.pl"),
            File("$dlc/tty")
        )
        propath(propath)

        // Set source include patterns
        ablFilter.set(listOf("**/*.p", "**/*.cls", "**/*.w"))

        // Execute OEDF compilation
        super.compile()
    }

    private fun parsePfFile(pfFile: File): Map<String, String> {
        // Parse .pf file for database connection parameters
        // Format: -db <name> -H <host> -S <port> -N tcp -U <user> -P <pass>
        // Return as OEDF-compatible connection map
        val connections = mutableMapOf<String, String>()
        if (!pfFile.exists()) return connections

        val tokens = pfFile.readText().split(Regex("\\s+")).filter { it.isNotBlank() }
        var i = 0
        var currentDbName: String? = null
        val currentParams = mutableMapOf<String, String>()

        fun flushCurrent() {
            if (currentDbName != null) {
                val host = currentParams["-H"] ?: "localhost"
                val port = currentParams["-S"] ?: ""
                val user = currentParams["-U"] ?: ""
                val pass = currentParams["-P"] ?: ""
                val transport = currentParams["-N"] ?: "tcp"
                val url = "$transport://$host:$port/$currentDbName"
                connections[url] = "$user:$pass"
            }
            currentParams.clear()
            currentDbName = null
        }

        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "-db" && i + 1 < tokens.size -> {
                    flushCurrent()
                    currentDbName = tokens[++i]
                }
                token.startsWith("-") && i + 1 < tokens.size -> {
                    currentParams[token] = tokens[++i]
                }
            }
            i++
        }
        flushCurrent()

        return connections
    }
}
```

Note: the exact `AblCompileTask` base class name, `propath()` method signature, `databaseConnections` format, and `destDir` property name will be adjusted during the discovery step (Step 1). The core structure — extending OEDF's native task, wiring properties, then delegating to `super.compile()` — is the invariant.

- [ ] **Step 3: Verify build**

```bash
cd D:/project/tatara && ./gradlew build
```

Expected: BUILD SUCCESSFUL. Resolve any import or API mismatches from discovery step.

- [ ] **Step 4: Publish to local maven**

```bash
cd D:/project/tatara && ./gradlew publishToMavenLocal
```

Expected: BUILD SUCCESSFUL, artifact at `~/.m2/repository/com/openedge/tatara/`.

- [ ] **Step 5: Commit**

```bash
cd D:/project/tatara && git add -A && git commit -m "feat: add OeCompileTask backed by progress.openedge.abl-base"
```

---

### Task 6: Update backend to consume tatara plugin

**Files:**
- Modify: `D:/ProjectWeb/backend/settings.gradle.kts`
- Modify: `D:/ProjectWeb/backend/build.gradle.kts`
- Delete: `D:/ProjectWeb/backend/buildSrc/` (entire directory)

- [ ] **Step 1: Update `settings.gradle.kts`**

Add `includeBuild` before the existing content:

```kotlin
/*
 * This file was generated by the Gradle 'init' task.
 *
 * The settings file is used to specify which projects to include in your build.
 * For more detailed information on multi-project builds, please refer to https://docs.gradle.org/9.6.1/userguide/multi_project_builds.html in the Gradle documentation.
 * This project uses @Incubating APIs which are subject to change.
 */

pluginManagement {
    includeBuild("D:/project/tatara")
}

rootProject.name = "backend"
```

- [ ] **Step 2: Update `build.gradle.kts`**

Apply the tatara plugin and re-register all tasks using tatara types:

```kotlin
/*
 * This file was generated by the Gradle 'init' task.
 *
 * This is a general purpose Gradle build.
 * Learn more about Gradle by exploring our Samples at https://docs.gradle.org/9.6.1/samples
 * This project uses @Incubating APIs which are subject to change.
 */
plugins {
    base
    id("com.openedge.tatara") version "1.0.0"
}

val sourceDir = project.layout.projectDirectory.dir("app")
val generatedWebDir = project.layout.buildDirectory.dir("generated/web")
val packagedDir = project.layout.buildDirectory.dir("generated/packaged")
val outputRcodeDir = project.layout.buildDirectory.dir("rcode")

val generateRoutes = tasks.register<com.openedge.tatara.GenerateRoutesTask>("generateRoutes") {
    group = "build"
    description = "Scans prefixed ABL source for @Route annotations and generates WebHandler shims."

    srcDir.set(packagedDir)
    generatedDir.set(generatedWebDir)
    cacheDir.set(project.layout.buildDirectory.dir("routeCache"))
    handlersDir.set(project.layout.buildDirectory.dir("generated/handlers"))

    dependsOn(prependPackage)
}

val prependPackage = tasks.register<com.openedge.tatara.PrependPackageTask>("prependPackage") {
    group = "build"
    description = "Prepends ABL package prefixes to .cls files from app/ into generated/packaged/"

    srcDir.set(sourceDir)
    outputDir.set(packagedDir)
}

val compileABL = tasks.register<com.openedge.tatara.OeCompileTask>("compileABL") {
    group = "build"
    description = "Compiles ABL source code to R-code using OpenEdge DevOps Framework."

    srcDir.set(packagedDir)
    generatedDir.set(generatedWebDir)
    rcodeDir.set(outputRcodeDir)

    dlcHome.set(project.providers.gradleProperty("openedge.dlcHome"))
    paramFile.set("config/database.pf")

    dependsOn(generateRoutes, prependPackage)
}

val packageWar = tasks.register<com.openedge.tatara.PackageWarTask>("packageWar") {
    group = "build"
    description = "Packages compiled ABL r-code and .handlers files into a WAR under build/dist/."

    rcodeDir.set(outputRcodeDir)
    handlersDir.set(project.layout.buildDirectory.dir("generated/handlers"))
    warFile.set(project.layout.buildDirectory.file("dist/${project.name}.war"))

    dependsOn(compileABL)
}

tasks.named("build") {
    description = "Compiles ABL source code to R-code and packages a deployable WAR."

    dependsOn(compileABL, packageWar)
}
```

Key changes:
- Added `id("com.openedge.tatara") version "1.0.0"` plugin
- Replaced inline import of task types with fully-qualified `com.openedge.tatara.*` references
- Removed `pctJarPath.set(...)` — no longer needed
- Added `paramFile.set("config/database.pf")` to compile task

- [ ] **Step 3: Delete buildSrc**

```bash
rm -rf D:/ProjectWeb/backend/buildSrc
```

- [ ] **Step 4: Verify backend builds**

```bash
cd D:/ProjectWeb/backend && ./gradlew tasks
```

Expected: `compileABL`, `generateRoutes`, `prependPackage`, `packageWar` all listed.

- [ ] **Step 5: Commit**

```bash
cd D:/ProjectWeb/backend && git add -A && git commit -m "refactor: migrate from buildSrc to tatara gradle plugin"
```

---

### Task 7: Integration test — full pipeline

**Files:**
- No file changes — verification only

- [ ] **Step 1: Verify prependPackage**

```bash
cd D:/ProjectWeb/backend && ./gradlew prependPackage --no-configuration-cache
```

Expected: BUILD SUCCESSFUL. Log shows "PrependPackageTask: N file(s) updated with prefix".

- [ ] **Step 2: Verify generateRoutes**

```bash
cd D:/ProjectWeb/backend && ./gradlew generateRoutes --no-configuration-cache
```

Expected: BUILD SUCCESSFUL. Route shims generated under `build/generated/web/`.

- [ ] **Step 3: Verify compileABL** (requires valid DLC and reachable databases)

```bash
cd D:/ProjectWeb/backend && ./gradlew compileABL --no-configuration-cache
```

Expected: BUILD SUCCESSFUL. R-code files at `build/rcode/`. If database unreachable, compilation may fail — note the error but confirm the OEDF invocation path is correct.

- [ ] **Step 4: Verify packageWar**

```bash
cd D:/ProjectWeb/backend && ./gradlew packageWar --no-configuration-cache
```

Expected: WAR at `build/dist/backend.war`.

- [ ] **Step 5: Verify full build**

```bash
cd D:/ProjectWeb/backend && ./gradlew build --no-configuration-cache
```

Expected: All tasks pass. WAR produced.

- [ ] **Step 6: Update `gradle.properties`** — remove `pctJarPath` if still present

```bash
grep "pctJarPath" D:/ProjectWeb/backend/gradle.properties
```

If found, remove the line:

```properties
openedge.pctJarPath=C:/PROGRESS/OpenEdge12/pct/PCT.jar
```

- [ ] **Step 7: Final commit**

```bash
cd D:/ProjectWeb/backend && git add -A && git commit -m "chore: remove pctJarPath from gradle.properties"
```
