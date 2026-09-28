plugins {
    alias(libs.plugins.android.library)

}

android {
    namespace = "org.itantra.text"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Literal coordinate (per module convention): used only by tests to exercise NumberSpeller
    // wiring against upstream ICU4J, since android.icu is an unimplemented stub in JVM unit tests.
    testImplementation("com.ibm.icu:icu4j:75.1")
}
