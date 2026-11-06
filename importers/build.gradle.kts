// :importers: pure Kotlin/JVM statement parsing and normalization (docs/13-import-pipeline.md §1).
// No Android and no I/O beyond byte arrays given to it, so every stage is unit-testable.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":domain"))
    // Android provides org.xmlpull.v1 at runtime; the jar is only needed to compile against it.
    compileOnly(libs.xmlpull)

    testImplementation(libs.kxml2)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                // docs/milestones/phase-6-statement-import.md §6: :importers line coverage >= 95 %
                minBound(95)
            }
        }
    }
}
