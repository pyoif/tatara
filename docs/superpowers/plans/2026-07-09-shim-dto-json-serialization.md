# Shim DTO JSON Serialization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the chunked longchar write in generated route shims with a `Progress.Json.ObjectModel.JsonObject` built by a new `Tatara.Api.DtoSerializer` runtime class. Response DTOs are serialized property-by-property at runtime; nested DTOs are serialized recursively at shim-emit time.

**Architecture:** Add a new ABL runtime class `Tatara.Api.DtoSerializer` with one static method `ToJsonObject(poDto, names, types, isDto)`. Extend `DtoParser` to recursively resolve nested DTO references and carry child `DtoInfo` on each `DtoProperty`. Extend `GenerateRouteTask.writeShim` to parse the full response DTO schema and emit nested `ToJsonObject` call sites (Shape A — depth grows in generated shim, no runtime table-of-tables). Generated shim assigns the resulting `JsonObject` to `oResponse:Entity`; PASOE handles wire serialization. Remove the chunked longchar write block.

**Tech Stack:** Kotlin (Gradle plugin code), ABL/OpenEdge PASOE (runtime class), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto` (user confirmed).
- ABL property types recognized as scalar: `CHARACTER`, `LONGCHAR`, `INTEGER`, `INT64`, `DECIMAL`, `LOGICAL`, `DATE`, `DATETIME`, `DATETIME-TZ`. Anything else is treated as a nested DTO class name unless cycle-broken.
- All properties always emitted; `?` / unset → JSON `null`.
- Cycle protection: at code-generation time only. Visited class names tracked in a `MutableSet<String>` passed into recursive `DtoParser.parse`. Once a class name is in the set, the property is marked `isDto=false` (treated as null at runtime).
- Runtime: no reflection, no DTO schema caching, no per-DTO generated serializer classes.
- Out of scope: `HANDLE`/`RAW`/`MEMPTR`/`BLOB`/`CLOB` property support (emit null), custom JSON property names, request DTO changes.
- Test framework: JUnit 5 (Jupiter). Tests live in `src/test/kotlin/`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/resources/Tatara/Api/DtoSerializer.cls` (new) | ABL runtime: `ToJsonObject(poDto, names, types, isDto)` static method. Scalar switch + recursive call sites. |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` (modify) | Recursive parse with visited-set. `DtoProperty` carries `isDto: Boolean` and `nested: DtoInfo?`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (modify) | Emit nested `ToJsonObject` call sites for response DTO. Remove chunked longchar write block. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (new) | JUnit 5 tests for recursive parse and cycle handling. |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` (new) | JUnit 5 test for shim emission with nested DTO schema. |

---

## Task 1: Add JUnit 5 test infrastructure

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (placeholder test only — proves the framework wires up)

**Interfaces:**
- Consumes: nothing
- Produces: `org.gradle.api.tasks.testing.Test` task available; `./gradlew test` runs JUnit 5 tests from `src/test/kotlin/`

- [ ] **Step 1: Add JUnit 5 dependency to `build.gradle.kts`**

Edit `build.gradle.kts` to add `testImplementation` for JUnit Jupiter and the JUnit Gradle plugin. After the existing `dependencies { ... }` block, add:

```kotlin
dependencies {
    implementation(gradleApi())
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

Do not change anything else in the file.

- [ ] **Step 2: Create placeholder test to prove framework is wired up**

Create `src/test/kotlin/com/pyoif/tatara/SmokeTest.kt`:

```kotlin
package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class SmokeTest {
    @Test
    fun `test framework works`() {
        assertTrue(true)
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run from project root:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. `build/reports/tests/test/index.html` should show 1 test passing.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/test/kotlin/com/pyoif/tatara/SmokeTest.kt
git commit -m "test: add JUnit 5 test infrastructure"
```

---

## Task 2: Extend DtoProperty + recursive DtoParser

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (new file, will be used in Task 3)

**Interfaces:**
- Consumes: nothing new
- Produces:
  - `DtoProperty(name: String, ablType: String, isRequired: Boolean = false, location: ParamLocation = ParamLocation.UNKNOWN, isExtent: Boolean = false, isDto: Boolean = false, nested: DtoInfo? = null)`
  - `DtoParser.parse(dtoClassName: String, srcRoot: File, visited: MutableSet<String> = mutableSetOf()): DtoInfo`
  - The list of primitive type names used to classify a property as scalar: `setOf("CHARACTER", "LONGCHAR", "INTEGER", "INT64", "DECIMAL", "LOGICAL", "DATE", "DATETIME", "DATETIME-TZ", "HANDLE", "RAW", "MEMPTR", "BLOB", "CLOB")` — anything outside this set is treated as a nested DTO class name. (`HANDLE`/`RAW`/`MEMPTR`/`BLOB`/`CLOB` will be marked `isDto=false` in Task 4 serializer and emit `null`; the parser classifies them as scalar.)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`:

```kotlin
package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DtoParserTest {

    @Test
    fun `parses flat DTO with primitive types`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        assertEquals(2, info.properties.size)
        assertEquals("id",   info.properties[0].name)
        assertEquals("INTEGER", info.properties[0].ablType)
        assertFalse(info.properties[0].isDto)
        assertEquals("name", info.properties[1].name)
        assertEquals("CHARACTER", info.properties[1].ablType)
        assertFalse(info.properties[1].isDto)
    }

    @Test
    fun `parses nested DTO recursively`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id     AS INTEGER.
                    DEFINE PUBLIC PROPERTY addr   AS com.example.Address.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val addrProp = info.properties.find { it.name == "addr" }!!
        assertTrue(addrProp.isDto)
        assertNotNull(addrProp.nested)
        assertEquals(1, addrProp.nested!!.properties.size)
        assertEquals("city", addrProp.nested!!.properties[0].name)
    }

    @Test
    fun `breaks cycles by marking repeated class as not-dto`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/A.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.A:
                    DEFINE PUBLIC PROPERTY b AS com.example.B.
            """.trimIndent())
        }
        File(src, "com/example/B.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.B:
                    DEFINE PUBLIC PROPERTY a AS com.example.A.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.A", src)
        val bProp = info.properties[0]
        assertTrue(bProp.isDto)
        assertNotNull(bProp.nested)
        val aProp = bProp.nested!!.properties[0]
        assertEquals("a", aProp.name)
        assertFalse(aProp.isDto)  // cycle broken
        assertNull(aProp.nested)
    }

    @Test
    fun `returns empty info for missing class`(@TempDir tmp: Path) {
        val info = DtoParser.parse("com.example.Missing", tmp.toFile())
        assertTrue(info.properties.isEmpty())
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 4 tests fail. The `DtoProperty` data class does not yet have `isDto`/`nested`, and `parse` is not recursive.

- [ ] **Step 3: Add `isDto` and `nested` to `DtoProperty`**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, change the `DtoProperty` data class to:

```kotlin
data class DtoProperty(
    val name: String,
    val ablType: String,
    val isRequired: Boolean = false,
    val location: ParamLocation = ParamLocation.UNKNOWN,
    val isExtent: Boolean = false,
    val isDto: Boolean = false,
    val nested: DtoInfo? = null
)
```

- [ ] **Step 4: Make `parse` recursive with cycle protection**

Replace the `parse` function in `DtoParser.kt` with:

```kotlin
fun parse(
    dtoClassName: String,
    srcRoot: File,
    visited: MutableSet<String> = mutableSetOf()
): DtoInfo {
    val file = resolveFile(dtoClassName, srcRoot) ?: return DtoInfo.EMPTY
    visited.add(dtoClassName)

    val properties = mutableListOf<DtoProperty>()
    val lines = file.readText().lines()

    var isReq = false
    var loc = ParamLocation.UNKNOWN

    val primitives = setOf(
        "CHARACTER", "LONGCHAR", "INTEGER", "INT64", "DECIMAL",
        "LOGICAL", "DATE", "DATETIME", "DATETIME-TZ",
        "HANDLE", "RAW", "MEMPTR", "BLOB", "CLOB"
    )

    for (line in lines) {
        val trimmed = line.trim()
        annotationRegex.findAll(trimmed).forEach { m ->
            when (m.groupValues[1].lowercase()) {
                "required" -> isReq = true
                "path" -> { loc = ParamLocation.PATH; isReq = false }
                "query" -> { loc = ParamLocation.QUERY; isReq = false }
                "body" -> { loc = ParamLocation.BODY; isReq = false }
            }
        }

        propDefRegex.find(trimmed)?.let { m ->
            val name = m.groupValues[1]
            val ablType = m.groupValues[2]
            val isExtent = m.groups[3]?.value != null

            val upperType = ablType.uppercase()
            val isScalar = primitives.contains(upperType) || upperType == "VOID"

            var isDto = false
            var nested: DtoInfo? = null
            if (!isScalar) {
                if (ablType in visited) {
                    isDto = false
                    nested = null
                } else {
                    isDto = true
                    nested = parse(ablType, srcRoot, visited)
                }
            }

            properties.add(DtoProperty(
                name = name,
                ablType = ablType,
                isRequired = isReq,
                location = loc,
                isExtent = isExtent,
                isDto = isDto,
                nested = nested
            ))
            isReq = false
        }
    }
    return DtoInfo(properties)
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): recursive DtoParser with cycle-safe nested DtoInfo"
```

---

## Task 3: Add `Tatara.Api.DtoSerializer` runtime class

**Files:**
- Create: `src/main/resources/Tatara/Api/DtoSerializer.cls`

**Interfaces:**
- Consumes: invoked from generated shims. See "Produces" for the exact signature.
- Produces:

```progress
METHOD PUBLIC STATIC Progress.Json.ObjectModel.JsonObject ToJsonObject(
    INPUT poDto   AS Progress.Lang.Object,
    INPUT pcNames AS CHARACTER EXTENT,
    INPUT pcTypes AS CHARACTER EXTENT,
    INPUT plIsDto AS LOGICAL EXTENT)
```

Returns a `JsonObject`. Behavior:
- `poDoc = ?` → empty `JsonObject`.
- For each `i` in `1..extent(pcNames)`:
  - Read `oVal` via `oDoc:GetClass():GetProperty(pcNames[i]):Get(oDoc) NO-ERROR`. If `ERROR-STATUS:ERROR`, treat `oVal` as `?`.
  - If `plIsDto[i] = TRUE`:
    - `oVal = ?` → `oJson:AddNull(pcNames[i])`.
    - Else → `oJson:Add(pcNames[i], ToJsonObject(oVal, ?, ?, ?))` (the shim passes the nested DTO's own prop tables — this method just recurses; in practice the shim has already inlined the recursive call shape, so this method only needs to call itself for arbitrary nested DTOs the shim chose to flatten inline).
  - Else (scalar):
    - `oVal = ?` → `oJson:AddNull(pcNames[i])`.
    - Dispatch on `pcTypes[i]`:
      - `INTEGER` / `INT64` → `oJson:Add(pcNames[i], INTEGER(oVal))`
      - `DECIMAL` → `oJson:Add(pcNames[i], DECIMAL(oVal))`
      - `LOGICAL` → `oJson:Add(pcNames[i], LOGICAL(oVal))`
      - `DATE` → `oJson:Add(pcNames[i], DATE(oVal))`
      - `DATETIME` / `DATETIME-TZ` → `oJson:Add(pcNames[i], DATETIME(oVal))`
      - `CHARACTER` / `LONGCHAR` → `oJson:Add(pcNames[i], CHARACTER(oVal))`
      - Anything else → `oJson:AddNull(pcNames[i])`.

- [ ] **Step 1: Create the runtime class file**

Create `src/main/resources/Tatara/Api/DtoSerializer.cls` with the following exact contents:

```progress
USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.DtoSerializer:

    METHOD PUBLIC STATIC JsonObject ToJsonObject(
        INPUT poDto   AS Progress.Lang.Object,
        INPUT pcNames AS CHARACTER EXTENT,
        INPUT pcTypes AS CHARACTER EXTENT,
        INPUT plIsDto AS LOGICAL EXTENT):

        DEFINE VARIABLE oJson AS JsonObject                       NO-UNDO.
        DEFINE VARIABLE oVal  AS Progress.Lang.Object            NO-UNDO.
        DEFINE VARIABLE i     AS INTEGER                         NO-UNDO.
        DEFINE VARIABLE cType AS CHARACTER                       NO-UNDO.
        DEFINE VARIABLE cVal  AS CHARACTER                       NO-UNDO.
        DEFINE VARIABLE oPropInfo AS Progress.Lang.Class.Property NO-UNDO.

        oJson = NEW JsonObject().

        IF NOT VALID-OBJECT(poDto) THEN RETURN oJson.

        DO i = 1 TO EXTENT(pcNames):
            ASSIGN
                cType = pcTypes[i]
                oVal  = ?
                .

            oPropInfo = ?.
            oVal = ?.
            IF VALID-OBJECT(poDto:GetClass()) THEN DO:
                oPropInfo = poDto:GetClass():GetProperty(pcNames[i]) NO-ERROR.
                IF VALID-OBJECT(oPropInfo) THEN
                    oVal = oPropInfo:Get(poDto) NO-ERROR.
            END.

            IF plIsDto[i] THEN DO:
                IF VALID-OBJECT(oVal) THEN
                    oJson:Add(pcNames[i], ToJsonObject(oVal, ?, ?, ?)).
                ELSE
                    oJson:AddNull(pcNames[i]).
            END.
            ELSE DO:
                IF NOT VALID-OBJECT(oVal) AND oVal = ? THEN
                    oJson:AddNull(pcNames[i]).
                ELSE DO:
                    CASE cType:
                        WHEN "INTEGER" OR WHEN "INT64" THEN
                            oJson:Add(pcNames[i], INTEGER(oVal)).
                        WHEN "DECIMAL" THEN
                            oJson:Add(pcNames[i], DECIMAL(oVal)).
                        WHEN "LOGICAL" THEN
                            oJson:Add(pcNames[i], LOGICAL(oVal)).
                        WHEN "DATE" THEN
                            oJson:Add(pcNames[i], DATE(oVal)).
                        WHEN "DATETIME" OR WHEN "DATETIME-TZ" THEN
                            oJson:Add(pcNames[i], DATETIME(oVal)).
                        WHEN "CHARACTER" OR WHEN "LONGCHAR" THEN DO:
                            cVal = IF VALID-OBJECT(oVal) THEN STRING(oVal) ELSE STRING(oVal).
                            oJson:Add(pcNames[i], cVal).
                        END.
                        OTHERWISE
                            oJson:AddNull(pcNames[i]).
                    END CASE.
                END.
            END.
        END.

        RETURN oJson.
    END METHOD.

END CLASS.
```

Note: the `ToJsonObject(oVal, ?, ?, ?)` call site in this method is a defensive runtime fallback. In practice, generated shims (Task 4) inline the nested call shape with the nested DTO's actual prop tables, so the recursive call inside `DtoSerializer` is rarely exercised — it only fires if a shim chooses to pass a top-level call with `isDto=true` slots, which the shim generator does not do. Keep the recursive call for completeness and to allow a future single-call shape.

- [ ] **Step 2: Verify the file exists and the syntax is well-formed ABL**

Open `src/main/resources/Tatara/Api/DtoSerializer.cls` and confirm:
- Line endings are CRLF (matching other `.cls` files in this directory; gradle should normalize via `.gitattributes` or by checking out the file).
- `USING` block is the first non-comment content.
- `CLASS ... :` line matches the casing of other Tatara.Api classes (e.g. `ErrorResponse.cls`).

Compare against `src/main/resources/Tatara/Api/ErrorResponse.cls` to confirm CRLF endings:

```bash
file src/main/resources/Tatara/Api/ErrorResponse.cls
file src/main/resources/Tatara/Api/DtoSerializer.cls
```

Both should report `CRLF` line endings.

If `DtoSerializer.cls` reports `LF`, run:

```bash
unix2mac src/main/resources/Tatara/Api/DtoSerializer.cls
```

(if `unix2mac` is not available, open the file in an editor that preserves CRLF and re-save, or use `sed -i 's/$/\r/' src/main/resources/Tatara/Api/DtoSerializer.cls`).

- [ ] **Step 3: Build the plugin and confirm the new resource is bundled**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. The new file is bundled into `build/resources/main/Tatara/Api/DtoSerializer.cls`. Verify:

```bash
ls -la build/resources/main/Tatara/Api/DtoSerializer.cls
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/Tatara/Api/DtoSerializer.cls
git commit -m "feat(runtime): add Tatara.Api.DtoSerializer.ToJsonObject"
```

---

## Task 4: Emit recursive `ToJsonObject` call sites from `GenerateRouteTask`

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (the `writeShim` method around lines 415–455)
- Create: `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`

**Interfaces:**
- Consumes: `RouteDef.responseDtoClassName` (existing field). The `DtoParser.parse` from Task 2 returns a `DtoInfo` with `nested` populated for non-primitive properties.
- Produces: generated shim contains, after the CATCH block and before `Tatara.Api.ResponseWriter:Write`:

  - A `DEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.` line.
  - A nested call tree: for each property of the response DTO, emit either:
    - Scalar slot: `oJson:Add("<name>", Tatara.Api.DtoSerializer:ToJsonObject(oResult, ["<name>"], ["<type>"], [no])).`
    - Nested DTO slot (recursion via shim, Shape A): for `isDto=true` properties, recursively emit a call that returns a `JsonObject` for the nested DTO and assign it. The nested call is built from the property's `nested: DtoInfo` and produces either:
      - `oJson:Add("<name>", Tatara.Api.DtoSerializer:ToJsonObject(oResult:<name>, NEW CHARACTER EXTENT [<n>] [...], NEW CHARACTER EXTENT [<n>] [...], NEW LOGICAL EXTENT [<n>] [...])).`
      - For deeper nesting, the nested call itself contains further `oJson:Add("<name>", Tatara.Api.DtoSerializer:ToJsonObject(oResult:<name>:<grandchild>, ...))` lines, achieved by walking the property tree depth-first.
  - After the `oJson` is fully populated:
    - `oResponse:StatusCode = 200.`
    - `oResponse:ContentType = "application/json".`
    - `oResponse:Entity = oJson.`
    - `Tatara.Api.ResponseWriter:Write(poRequest, oResponse).`
    - `RETURN 0.`
  - The `oWriter` / `iOffset` definitions and the `DO WHILE iOffset <= LENGTH(oResult:data)` chunked-write block are removed (only when `hasResponseDto` is true).

  Note: the same flat top-level call shape (`oJson:Add(name, ToJsonObject(oResult, [...], [...], [...]))`) is used even for scalar-only DTOs, so the generated code is uniform.

- [ ] **Step 1: Write the failing emission test**

Create `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`:

```kotlin
package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateRouteTaskEmitTest {

    @Test
    fun `emits DtoSerializer call for flat response DTO`(@TempDir tmp: Path) {
        val src = tmp.toFile().resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }

        val genDir = tmp.toFile().resolve("gen").toFile()
        val task = GenerateRoutesTask::class.java.getDeclaredConstructor().newInstance()
        // We invoke writeShim via reflection; the helper below isolates that.
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("Tatara.Api.DtoSerializer:ToJsonObject"), "shim should call DtoSerializer")
        assertTrue(shim.contains("\"id\""),  "shim should reference id prop")
        assertTrue(shim.contains("\"name\""),"shim should reference name prop")
        assertTrue(shim.contains("oResponse:Entity = oJson"), "shim should set Entity to oJson")
        assertFalse(shim.contains("oResult:data"), "shim should NOT chunk-write oResult:data")
    }

    @Test
    fun `emits recursive DtoSerializer call for nested response DTO`(@TempDir tmp: Path) {
        val src = tmp.toFile().resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
                    DEFINE PUBLIC PROPERTY zip  AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY addr AS com.example.Address.
            """.trimIndent())
        }

        val genDir = tmp.toFile().resolve("gen").toFile()
        val task = GenerateRoutesTask::class.java.getDeclaredConstructor().newInstance()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("oResult:addr"), "shim should reference oResult:addr for nested prop")
        assertTrue(shim.contains("\"city\""), "shim should include nested prop city")
        assertTrue(shim.contains("\"zip\""),  "shim should include nested prop zip")
    }
}
```

- [ ] **Step 2: Create the test helper that drives `writeShim`**

Create `src/test/kotlin/com/pyoif/tatara/ShimEmitHelper.kt`:

```kotlin
package com.pyoif.tatara

import org.gradle.api.file.DirectoryProperty
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.lang.reflect.Method

object ShimEmitHelper {

    fun invokeWriteShim(
        task: Any,
        srcDir: File,
        genDir: File,
        routePath: String,
        routeDef: GenerateRoutesTask.RouteDef
    ): String {
        // Wire DirectoryProperty fields via ProjectBuilder (DirectoryProperty requires a Project).
        val project = ProjectBuilder.builder().build()
        val srcProp = DirectoryProperty::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance().also { it.set(srcDir) }
        val genProp = DirectoryProperty::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance().also { it.set(genDir) }

        task.javaClass.getDeclaredField("srcDir").apply { isAccessible = true }.set(task, srcProp)
        task.javaClass.getDeclaredField("generatedDir").apply { isAccessible = true }.set(task, genProp)

        // Reflectively call private writeShim(routePath, routes, outDir, template)
        val writeShim: Method = task.javaClass.getDeclaredMethod(
            "writeShim", String::class.java, List::class.java, File::class.java, String::class.java
        )
        writeShim.isAccessible = true
        genDir.mkdirs()
        writeShim.invoke(task, routePath, listOf(routeDef), genDir, TEMPLATE)

        val fsPath = routePath.replace(Regex("\\{[^}]+\\}"), "_")
        return File(genDir, "$fsPath.cls").readText()
    }

    private val TEMPLATE = """
        USING Progress.Lang.*.
        USING OpenEdge.Web.WebResponseWriter.
        USING OpenEdge.Net.HTTP.StatusCodeEnum.
        USING OpenEdge.Web.WebHandler.
        USING Tatara.Api.*.
        ${'$'}{USING_BLOCK}

        BLOCK-LEVEL ON ERROR UNDO, THROW.

        CLASS ${'$'}{SHIM_CLASS_NAME} INHERITS WebHandler:
        ${'$'}{METHOD_HANDLERS}
        END CLASS.
    """.trimIndent()
}
```

- [ ] **Step 3: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: both tests fail. The current `writeShim` still emits the chunked longchar write block and does not call `DtoSerializer`.

- [ ] **Step 4: Refactor `writeShim` to emit `DtoSerializer` calls**

In `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`:

1. Add `import com.pyoif.tatara.DtoParser` if not present (it is, since it's used for request DTOs).
2. After the existing CATCH blocks (around line 440, after `CATCH errApp ... END.`), replace the chunked-write block with a recursive emission.

Add a new private helper method on the task class (below `writeShim`):

```kotlin
private fun emitResponseJson(
    sb: StringBuilder,
    oResultAccessor: String,
    dtoInfo: DtoParser.DtoInfo
) {
    dtoInfo.properties.forEach { prop ->
        val propAccessor = "$oResultAccessor:${prop.name}"
        if (prop.isDto && prop.nested != null) {
            // Recursive call: nested DTO gets its own ToJsonObject, returned JsonObject is added.
            val nestedNames = prop.nested.properties.joinToString(", ") { "\"${it.name}\"" }
            val nestedTypes = prop.nested.properties.joinToString(", ") { "\"${it.ablType}\"" }
            val nestedIsDto = prop.nested.properties.joinToString(", ") {
                if (it.isDto) "yes" else "no"
            }
            val n = prop.nested.properties.size
            sb.append("\t\toJson:Add(\"${prop.name}\", Tatara.Api.DtoSerializer:ToJsonObject(\r\n")
            sb.append("\t\t\t$propAccessor,\r\n")
            sb.append("\t\t\tNEW CHARACTER EXTENT [$n] [$nestedNames],\r\n")
            sb.append("\t\t\tNEW CHARACTER EXTENT [$n] [$nestedTypes],\r\n")
            sb.append("\t\t\tNEW LOGICAL   EXTENT [$n] [$nestedIsDto])).\r\n")
        } else {
            // Scalar slot: top-level call with single-element extents.
            sb.append("\t\toJson:Add(\"${prop.name}\", Tatara.Api.DtoSerializer:ToJsonObject(\r\n")
            sb.append("\t\t\t$propAccessor,\r\n")
            sb.append("\t\t\tNEW CHARACTER EXTENT [1] [\"${prop.name}\"],\r\n")
            sb.append("\t\t\tNEW CHARACTER EXTENT [1] [\"${prop.ablType}\"],\r\n")
            sb.append("\t\t\tNEW LOGICAL   EXTENT [1] [no])).\r\n")
        }
    }
}
```

In the existing `writeShim` method, locate the response-emission block (the lines starting with `if (hasResponseDto) { methodHandlersBlock.append("\t\toWriter:Open()....`) and the `oResponse:ContentType = "application/json".` line right after. Replace the entire block (from `if (hasResponseDto)` through `methodHandlersBlock.append("\t\treturn 0.\r\n")` for the response block) with:

```kotlin
if (hasResponseDto) {
    methodHandlersBlock.append("\t\tDEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
    methodHandlersBlock.append("\t\toJson = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
    val responseInfo = DtoParser.parse(def.responseDtoClassName!!, srcRoot)
    emitResponseJson(methodHandlersBlock, "oResult", responseInfo)
}
methodHandlersBlock.append("\t\toResponse:StatusCode = 200.\r\n")
methodHandlersBlock.append("\t\toResponse:ContentType = \"application/json\".\r\n")
if (hasResponseDto) {
    methodHandlersBlock.append("\t\toResponse:Entity = oJson.\r\n")
}
methodHandlersBlock.append("\t\tTatara.Api.ResponseWriter:Write(poRequest, oResponse).\r\n")
methodHandlersBlock.append("\t\tRETURN 0.\r\n")
```

Also remove the `oWriter` and `iOffset` `DEFINE VARIABLE` lines from the per-handler preamble when `hasResponseDto` is true. In the current code (around lines 285–290):

```kotlin
methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse AS OpenEdge.Web.WebResponse NO-UNDO.\r\n")
methodHandlersBlock.append("\t\tDEFINE VARIABLE oWriter AS OpenEdge.Web.WebResponseWriter NO-UNDO.\r\n")
methodHandlersBlock.append("\t\tDEFINE VARIABLE iOffset AS INTEGER NO-UNDO.\r\n")
```

Change to:

```kotlin
methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse AS OpenEdge.Web.WebResponse NO-UNDO.\r\n")
if (hasResponseDto) {
    methodHandlersBlock.append("\t\tDEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
}
```

- [ ] **Step 5: Run the new tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: both tests pass. The shim now references `Tatara.Api.DtoSerializer:ToJsonObject`, contains the prop names, sets `oResponse:Entity = oJson`, and does not contain `oResult:data`.

- [ ] **Step 6: Run the full test suite to verify no regressions**

```bash
./gradlew test
```

Expected: all tests pass (`SmokeTest`, `DtoParserTest`, `GenerateRouteTaskEmitTest`).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt src/test/kotlin/com/pyoif/tatara/ShimEmitHelper.kt
git commit -m "feat(shim): emit recursive DtoSerializer calls for response DTOs"
```

---

## Task 5: End-to-end manual verification (in PASOE)

**Files:** none modified

This task is a manual smoke test inside a downstream project that uses the Tatara plugin. It cannot run from the plugin repo itself.

- [ ] **Step 1: Build the plugin to a local Maven repo**

```bash
./gradlew publishToMavenLocal
```

Expected: `BUILD SUCCESSFUL`. The artifact lands in `~/.m2/repository/com/pyoif/tatara/1.0.0/`.

- [ ] **Step 2: In a downstream project that uses the Tatara plugin, regenerate shims**

In a downstream project (e.g. an ABL sample project), run:

```bash
./gradlew tataraGenerateRoutes
```

Inspect the generated `.cls` for a route that has a response DTO with a nested DTO reference. Confirm:
- It contains `Tatara.Api.DtoSerializer:ToJsonObject` calls.
- The top-level DTO properties appear as flat slots.
- Each nested DTO property has its own recursive call with the child DTO's prop tables.
- It does not contain `oResult:data` chunked write.

- [ ] **Step 3: Start the PASOE server and hit a route that returns a nested DTO**

Use a downstream project's `gradlew tataraRun` (or equivalent) to start PASOE. Then `curl` an endpoint:

```bash
curl -i http://localhost:<port>/<svc>/<route>
```

Expected: HTTP 200, `Content-Type: application/json`, body is a JSON object whose keys match the response DTO's properties, with nested objects expanded to the full tree.

- [ ] **Step 4: Test null and empty DTO cases**

In a sample controller, return an instance with all properties `?`. Re-run the request.

Expected: HTTP 200, body is `{"<prop1>": null, "<prop2>": null, ...}` (every key present, all null).

- [ ] **Step 5: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 DtoSerializer → Task 3.
- Spec §2 DtoParser recursion + cycle → Task 2.
- Spec §3 GenerateRouteTask emission (Shape A) → Task 4.
- Spec §4 error handling → Task 4 (no internal CATCH; falls through to existing shim CATCH).
- Spec §5 testing → Tasks 1, 2, 4, 5.
- No placeholders. All test code shown. All ABL code shown. All Kotlin signatures match across tasks.
- Type consistency: `DtoProperty.isDto`/`nested` are introduced in Task 2 and consumed in Task 4. `DtoParser.parse` signature is identical (new param `visited` has a default).
