plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Static analysis for every module (docs/18-coding-standards.md §1). Findings are fixed or excluded
// in config/detekt/detekt.yml with a written reason; no baseline files.
subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.get())
    }
}

// ---------------------------------------------------------------------------------------------
// Money-safety rule (NFR-01, docs/18-coding-standards.md §3).
// Fails the build if floating-point types, conversions, or literals appear in main sources of
// money-handling modules. Comments and string literals are ignored. A line may opt out only with
// a trailing `// money-safety:allow <reason>` comment, which is reported for review.
// ---------------------------------------------------------------------------------------------
abstract class MoneySafetyCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** When true, the task succeeds only if violations ARE found (rule self-test on a bad fixture). */
    @get:Input
    abstract val expectViolations: Property<Boolean>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val forbidden = listOf(
            Regex("""\b(Double|Float)\b""") to "floating-point type",
            Regex("""\.to(Double|Float)\s*\(""") to "floating-point conversion",
            Regex("""(?<![\w.])\d+\.\d+([eE][+-]?\d+)?[fF]?\b""") to "floating-point literal",
            Regex("""(?<![\w.])\d+[fF]\b""") to "float literal",
            Regex("""(?<![\w.])\d+[eE][+-]?\d+\b""") to "exponent literal",
        )
        val violations = mutableListOf<String>()
        val allowed = mutableListOf<String>()
        sources.asFileTree.matching { include("**/*.kt") }.forEach { file ->
            val stripped = stripCommentsAndStrings(file.readText())
            stripped.lines().forEachIndexed { i, line ->
                val raw = file.readLines().getOrElse(i) { "" }
                forbidden.forEach { (regex, what) ->
                    if (regex.containsMatchIn(line)) {
                        val where = "${file.relativeTo(rootDirectory.get().asFile)}:${i + 1}: $what"
                        if (raw.contains("money-safety:allow")) allowed += where else violations += where
                    }
                }
            }
        }
        allowed.forEach { logger.warn("money-safety: allowed by annotation -> $it") }
        if (expectViolations.get()) {
            if (violations.isEmpty()) throw GradleException("money-safety self-test: expected violations in fixture, found none")
            logger.lifecycle("money-safety self-test: rule detected ${violations.size} violation(s) in fixture, as expected")
        } else if (violations.isNotEmpty()) {
            throw GradleException("money-safety: floating point is forbidden in money code:\n" + violations.joinToString("\n"))
        }
    }

    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = src.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < src.length && !(src[i] == '*' && src.getOrNull(i + 1) == '/')) { if (src[i] == '\n') out.append('\n'); i++ }
                    i += 2
                }
                src.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) { if (src[i] == '\n') out.append('\n'); i++ }
                    i += 3; out.append("\"\"")
                }
                c == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') { if (src[i] == '\\') i++; i++ }
                    i++; out.append("\"\"")
                }
                c == '\'' && i + 2 < src.length && (src[i + 2] == '\'' || src[i + 1] == '\\') -> {
                    i++; while (i < src.length && src[i] != '\'') { if (src[i] == '\\') i++; i++ }; i++; out.append("' '")
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

val moneySafetyModules = listOf("domain", "importers", "data")

tasks.withType<MoneySafetyCheck>().configureEach {
    rootDirectory.set(layout.projectDirectory)
}

val moneySafety = tasks.register<MoneySafetyCheck>("moneySafety") {
    group = "verification"
    description = "Fails on floating-point usage in money-handling modules (NFR-01)."
    moneySafetyModules.forEach { m -> sources.from(fileTree("$m/src/main") { include("**/*.kt") }) }
    expectViolations.set(false)
}

val moneySafetySelfTest = tasks.register<MoneySafetyCheck>("moneySafetySelfTest") {
    group = "verification"
    description = "Proves the money-safety rule detects violations in a deliberately bad fixture."
    sources.from(fileTree("config/money-safety/fixtures/bad"))
    expectViolations.set(true)
}

val moneySafetyCleanFixture = tasks.register<MoneySafetyCheck>("moneySafetyCleanFixture") {
    group = "verification"
    description = "Proves the money-safety rule does not flag comments, strings, or integer code."
    sources.from(fileTree("config/money-safety/fixtures/good"))
    expectViolations.set(false)
}


// ---------------------------------------------------------------------------------------------
// Test-discovery guard. JUnit 5 silently ignores @Test methods that return a value, for example
// `fun t() = runBlocking { checkAll(...) }`, which returns kotest's PropertyContext. Found 2026-11-05
// after 5 property tests had never run. Expression-bodied tests must use runBlocking<Unit> or runTest.
// ---------------------------------------------------------------------------------------------
abstract class TestDiscoveryCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val expectViolations: Property<Boolean>

    @TaskAction
    fun check() {
        val pattern = Regex("""@Test\s+fun\s+(`[^`]*`)\(\)\s*=\s*runBlocking\s*\{""")
        val violations =
            sources.asFileTree.matching { include("**/*.kt") }.flatMap { f ->
                pattern.findAll(f.readText()).map { "${f.name}: ${it.groupValues[1]}" }.toList()
            }
        if (expectViolations.get()) {
            if (violations.isEmpty()) throw GradleException("test-discovery self-test: expected a violation in the fixture")
            logger.lifecycle("test-discovery self-test: detected ${violations.size} value-returning test(s), as expected")
        } else if (violations.isNotEmpty()) {
            throw GradleException(
                "Value-returning @Test methods are silently skipped by JUnit 5: " + violations.joinToString("; "),
            )
        }
    }
}

val testDiscovery = tasks.register<TestDiscoveryCheck>("testDiscovery") {
    group = "verification"
    description = "Fails on @Test functions that JUnit 5 would silently skip."
    listOf("domain", "importers", "data", "app").forEach { m -> sources.from(fileTree("$m/src") { include("*Test*/**/*.kt", "test/**/*.kt") }) }
    expectViolations.set(false)
}

val testDiscoverySelfTest = tasks.register<TestDiscoveryCheck>("testDiscoverySelfTest") {
    group = "verification"
    sources.from(fileTree("config/test-discovery/fixtures/bad"))
    expectViolations.set(true)
}

tasks.register("check") {
    group = "verification"
    description = "Root-level checks."
    dependsOn(moneySafety, moneySafetySelfTest, moneySafetyCleanFixture, testDiscovery, testDiscoverySelfTest)
}
