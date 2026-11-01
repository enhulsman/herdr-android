import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repoRoot: Directory = rootProject.layout.projectDirectory.dir("..")
val appVersion = repoRoot.file("VERSION").asFile.readText().trim()
// Same formula as scripts/check-release-tag.sh, which rejects a release whose
// VERSION does not fit it; the release workflow checks the APK carries its result.
val (major, minor, patch) = appVersion.split('.').map(String::toInt)

android {
    namespace = "dev.herdr.android"
    compileSdk = 36
    // Same NDK as scripts/build-rust-android.sh; AGP strips the .so files with it.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "dev.herdr.android"
        minSdk = 31
        targetSdk = 36
        versionName = appVersion
        versionCode = major * 10000 + minor * 100 + patch
        manifestPlaceholders["appLabel"] = "herdr"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        // Installs next to the release from Obtainium, signed with the debug key.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "herdr debug"
        }
        // The terminal spike (task M0.5, src/spike/): optimized Rust with
        // libghostty-vt linked in, not debuggable so frame times are realistic.
        // Needs scripts/build-ghostty.sh for both Android targets. CI and
        // releases never build it.
        create("spike") {
            initWith(getByName("debug"))
            isDebuggable = false
            isJniDebuggable = false
            applicationIdSuffix = ".spike"
            manifestPlaceholders["appLabel"] = "herdr spike"
            matchingFallbacks += "debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // scripts/build-rust-android.sh writes here; each variant packages its own copy (BuildRust).
    sourceSets["main"].jniLibs.directories.clear()
}

// The Rust core for one variant: runs scripts/build-rust-android.sh, which writes
// src/main/jniLibs/<abi>/ and build/generated/uniffi/kotlin, and copies both into
// this task's outputs in the same step, so a debug and a release build in one
// Gradle run cannot package each other's library.
abstract class BuildRust @Inject constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:Input abstract val release: Property<Boolean>
    @get:Input abstract val features: ListProperty<String>
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputDirectory abstract val jniLibs: DirectoryProperty
    @get:OutputDirectory abstract val kotlin: DirectoryProperty

    init {
        doNotTrackState("cargo decides what to rebuild")
    }

    @TaskAction
    fun run() {
        val root = repoRoot.get()
        exec.exec {
            workingDir(root)
            commandLine(root.file("scripts/build-rust-android.sh").asFile.path)
            if (release.get()) args("--release")
            if (features.get().isNotEmpty()) args("--features", features.get().joinToString(","))
        }
        fs.sync {
            from(root.dir("android/app/src/main/jniLibs"))
            into(jniLibs)
        }
        fs.sync {
            from(root.dir("android/app/build/generated/uniffi/kotlin"))
            into(kotlin)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val buildRust = tasks.register<BuildRust>("buildRust${variant.name.replaceFirstChar(Char::uppercase)}") {
            group = "build"
            description = "Builds the Rust core and its Kotlin bindings for ${variant.name}."
            release.set(variant.buildType != "debug")
            features.set(if (variant.buildType == "spike") listOf("vt") else emptyList())
            repoRoot.set(rootProject.layout.projectDirectory.dir(".."))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRust, BuildRust::jniLibs)
        variant.sources.kotlin?.addGeneratedSourceDirectory(buildRust, BuildRust::kotlin)
        // Releases go to phones only: one arm64-v8a APK. Debug keeps x86_64 for the emulator.
        if (variant.buildType == "release") {
            variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.jna) {
        artifact { type = "aar" }
    }
    testImplementation(libs.junit)
    // The jar carries JNA's dispatch library for the host JVM; the aar only has Android's.
    testImplementation(libs.jna)
}

// Unit tests load a host build of the same library through the generated
// bindings (CoreBridgeTest), so the bridge is checked without a device.
val buildRustHost = tasks.register<Exec>("buildRustHost") {
    group = "build"
    description = "Builds libherdr_mobile.so for the host JVM's unit tests."
    workingDir(repoRoot)
    // The same target directory as the jna.library.path below, whatever the environment says.
    environment("CARGO_TARGET_DIR", repoRoot.dir("target").asFile.path)
    commandLine("cargo", "build", "-p", "herdr-mobile", "--locked")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("jna.library.path", repoRoot.dir("target/debug").asFile.path)
    systemProperty("herdr.version", appVersion)
}
