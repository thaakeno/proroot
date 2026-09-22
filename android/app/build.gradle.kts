import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

data class RuntimeLibrary(val name: String, val sha256: String, val assetId: Long)

val prorootVersion = "v1.2.8"
val buildGitSha = System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() } ?: "local"

android {
    namespace = "dev.thaakeno.proroot"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    defaultConfig {
        applicationId = "dev.thaakeno.proroot"
        minSdk = 30
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "PROROOT_VERSION", "\"$prorootVersion\"")
        buildConfigField("String", "GIT_SHA", "\"$buildGitSha\"")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-O3")
            }
        }
    }

    signingConfigs {
        create("prorootDebug") {
            storeFile = file("../debug/proroot-debug.keystore")
            storePassword = "android"
            keyAlias = "prorootdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("prorootDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
}

val prorootLibraries = listOf(
    RuntimeLibrary("libproroot.so", "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01", 449758014L),
    RuntimeLibrary("libproroot-runtime.so", "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34", 449758016L),
    RuntimeLibrary("libproroot-bridge.so", "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f", 449758015L),
    RuntimeLibrary("libproroot-linker.so", "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee", 449758017L),
    RuntimeLibrary("libproroot-stub-loader.so", "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0", 449758013L),
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val generatedProrootDir = layout.buildDirectory.dir("generated/prorootJniLibs/arm64-v8a")
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/prorootJniLibs"))

val prepareProrootLibraries by tasks.registering {
    outputs.dir(generatedProrootDir)
    doLast {
        val outDir = generatedProrootDir.get().asFile.apply { mkdirs() }
        prorootLibraries.forEach { runtimeLib ->
            val output = File(outDir, runtimeLib.name)
            if (!output.exists() || sha256(output) != runtimeLib.sha256) {
                val sources = listOf(
                    URL(
                        "https://github.com/coderredlab/proroot/releases/download/$prorootVersion/${runtimeLib.name}",
                    ) to false,
                    URL(
                        "https://api.github.com/repos/coderredlab/proroot/releases/assets/${runtimeLib.assetId}",
                    ) to true,
                )
                val githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
                var lastFailure: Throwable? = null
                var downloaded = false

                attemptLoop@ for (attempt in 0 until 4) {
                    for ((url, apiAsset) in sources) {
                        try {
                            output.delete()
                            val connection = url.openConnection().apply {
                                connectTimeout = 20_000
                                readTimeout = 60_000
                                setRequestProperty("User-Agent", "proroot-android-build")
                                if (apiAsset) {
                                    setRequestProperty("Accept", "application/octet-stream")
                                    githubToken?.let {
                                        setRequestProperty("Authorization", "Bearer $it")
                                    }
                                }
                            }
                            connection.getInputStream().buffered().use { input ->
                                output.outputStream().buffered().use(input::copyTo)
                            }
                            check(sha256(output) == runtimeLib.sha256) {
                                "Checksum failed for ${runtimeLib.name}"
                            }
                            downloaded = true
                            lastFailure = null
                            break@attemptLoop
                        } catch (failure: Throwable) {
                            lastFailure = failure
                            output.delete()
                        }
                    }
                    if (attempt < 3) Thread.sleep(1_500L * (attempt + 1))
                }

                check(downloaded && output.exists()) {
                    "Could not fetch verified ${runtimeLib.name}: ${lastFailure?.message}"
                }
            }
            check(sha256(output) == runtimeLib.sha256) { "Checksum failed for ${runtimeLib.name}" }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareProrootLibraries)
}
