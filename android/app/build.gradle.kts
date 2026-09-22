import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

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

data class RuntimeLibrary(val name: String, val sha256: String, val assetId: Long)

val prorootVersion = "v1.2.7.1"
val prorootLibraries = listOf(
    RuntimeLibrary("libproroot.so", "018132fff13bcbc8871d25da6b695cad2b583a1f143236de7cbd9aa7c646770b", 428610458L),
    RuntimeLibrary("libproroot-runtime.so", "af1846ef0648f2488a069d9b78a9448f7fa662e6249130577fe2f2d6ebcd32f3", 428610460L),
    RuntimeLibrary("libproroot-bridge.so", "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f", 428610461L),
    RuntimeLibrary("libproroot-linker.so", "c8bd8c42b3eaf58e0635a97902b4808bfd32c57a47f33861aca5a8d42da662b7", 428610457L),
    RuntimeLibrary("libproroot-stub-loader.so", "ef25133f0250c5353f1eb77770e91062dd3fe2f66a3c1288e47051bded8e9341", 428610459L),
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
