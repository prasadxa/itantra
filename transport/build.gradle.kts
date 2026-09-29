plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.itantra.transport"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.android)
    // X25519 / Ed25519 / ChaCha20-Poly1305 primitives for the binary frame's optional encryption
    // and ALERT signing (see crypto/ and transport/README.md "Crypto choices" for the size trade-off).
    implementation(libs.bouncycastle.prov)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
