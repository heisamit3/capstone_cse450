plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.example.capstone.extractor"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        ndk {
            // The only ABI any target device uses. OpenCV ships four; the other
            // three would add ~120 MB to the APK for nothing.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Nothing here touches android.*, but a stray Log call must not throw.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.opencv)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.opencv.jvm)
    // The fixture is read from sample/layout.json rather than transcribed; android.jar
    // stubs org.json out, so the unit tests need the real one.
    testImplementation(libs.json)
}

// The OpenCV Android AAR carries no host-loadable native library, so a JVM unit
// test cannot use it: System.loadLibrary would find only arm64/x86 Android .so
// files. Drop it from the unit-test classpaths and let the openpnp build stand in
// - identical org.opencv.* API (checked signature by signature against 4.11), with
// desktop .dll/.so/.dylib bundled and loaded by nu.pattern.OpenCV.loadLocally().
//
// Nothing in src/main may reference org.opencv.android.* on a path a test reaches;
// that package exists only in the AAR. See OpenCvNative.kt.
configurations.matching {
    it.name.startsWith("test") &&
        (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}
