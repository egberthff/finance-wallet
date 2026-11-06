// :data: Room + SQLCipher persistence, key management, integrity checks (ADR-0002).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "ph.financewallet.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // MigrationTestHelper reads the exported schema JSON from test assets (Robolectric).
    sourceSets
        .getByName("test")
        .assets.directories
        .add("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// Robolectric (android-36 runtime) reflects into JDK internals for FileDescriptor handling; JDK 17+
// blocks this by default. Test-JVM only; production code is unaffected.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
    // SchemaConformanceTest reads the reference DDL: a doc-only change must re-run the tests.
    inputs
        .file(rootProject.file("docs/11-database-schema.md"))
        .withPropertyName("referenceSchemaDoc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

room {
    // Exported schema JSON is committed (docs/11-database-schema.md §6).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))
    api(project(":importers"))
    implementation(libs.bouncycastle.prov)
    // api: FinanceDatabase (a RoomDatabase) is part of :data's public types; callers still go through services.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
