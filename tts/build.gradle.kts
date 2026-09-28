plugins {
    alias(libs.plugins.android.library)

}

android {
    namespace = "org.itantra.tts"
    compileSdk = 37
    // Only NDK/CMake versions installed under ~/Library/Android/sdk at time of writing; see
    // tts/src/main/cpp/README.md for how these were picked.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            // Physical target devices only (docs/design.md: "8 GB arm64 phone") — no emulator ABIs.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // GGML_NATIVE=OFF: don't autodetect the *build host's* CPU features, cross-compiling
                // for arm64-v8a. OpenMP and every extra ggml backend are off to keep this a plain
                // CPU build; c++_shared matches sherpa-onnx's own STL choice (see packaging{} below).
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1" // only version under ~/Library/Android/sdk/cmake
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // sherpa-onnx's .so's don't bundle libc++_shared.so themselves (checked: only
            // libonnxruntime/libsherpa-onnx-{c,cxx}-api/libsherpa-onnx-jni); pickFirst is a
            // defensive default in case that changes upstream.
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.sherpa.onnx)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
