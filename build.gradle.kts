import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "pl.blizinski"
version = "0.1.0"

// Publishing more than one target (android + wasm-js) breaks JitPack's Gradle module metadata
// for downstream KMP consumers: their own android-target dependency resolution ends up being
// offered only the wasm-js variant of this library and fails ("No matching variant..."),
// regardless of how the consumer's own dependency is declared. Since nothing consumes this
// library's wasmJs artifact via JitPack (it's a same-repo proof-of-concept target used only
// through the local sibling-checkout composite build), it's left out of JitPack builds
// entirely (set via `-PjitpackBuild=true` in jitpack.yml).
val isJitpackBuild = project.hasProperty("jitpackBuild")

kotlin {
    android {
        namespace = "pl.blizinski.tasksync"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
        }
    }

    // wasmJs proof-of-concept target — see TaskCompass's
    // Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md. Additive only: does not touch
    // the android {} block above. Skipped on JitPack — see isJitpackBuild above.
    if (!isJitpackBuild) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.work.runtime.ktx)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}
