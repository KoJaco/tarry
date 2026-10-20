plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "me.tarry.core.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        // Exported Room schemas are read by MigrationTestHelper in JVM (Robolectric) tests.
        getByName("test").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reaches into JDK internals; required on JDK 17+ (we run JDK 25).
            it.jvmArgs(
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
