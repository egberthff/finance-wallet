import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ph.financewallet.app"
    compileSdk = 37

    defaultConfig {
        // Owner decision 2026-11-06 (Q-22): app "Finance Manager". Permanent once published; code packages keep ph.financewallet.
        applicationId = "ph.financemanager.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0-beta"
    }

    // Release signing comes from a properties file outside the repository (SEC-02): -Pfm.signing=<path> or the default
    // path below. Without it, assembleRelease still builds, but the APK is unsigned.
    val signingFile =
        file(
            providers.gradleProperty("fm.signing").orNull
                ?: "${System.getProperty("user.home")}/dev/keys/finance-manager-signing.properties",
        )
    if (signingFile.isFile) {
        val props = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric (android-36 runtime) reflects into JDK internals; test JVM only (same as :data).
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

// ADR-0010: the merged manifest of every variant must not request network access.
abstract class CheckNoNetworkPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        val forbidden =
            listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
                .filter { text.contains("\"$it\"") }
        if (forbidden.isNotEmpty()) {
            throw GradleException("ADR-0010 violated: merged manifest requests ${forbidden.joinToString()}")
        }
        logger.lifecycle("ADR-0010: merged manifest has no network permissions")
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val checkTask =
            tasks.register<CheckNoNetworkPermission>("check${name}NoNetworkPermission") {
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            }
        tasks.named("check").configure { dependsOn(checkTask) }
    }
}
