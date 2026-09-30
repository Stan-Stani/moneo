plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.poketrek"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.poketrek"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DM_CORE_GBA=ON",
                    "-DM_CORE_GB=OFF",
                    "-DUSE_FFMPEG=OFF",
                    "-DUSE_PNG=OFF",
                    "-DUSE_LIBZIP=OFF",
                    "-DUSE_ZLIB=ON",
                    "-DUSE_MINIZIP=OFF",
                    "-DUSE_LZMA=OFF",
                    "-DUSE_SQLITE3=OFF",
                    "-DUSE_DEBUGGERS=OFF",
                    "-DUSE_GDB_STUB=OFF",
                    "-DBUILD_QT=OFF",
                    "-DBUILD_SDL=OFF",
                    "-DBUILD_LIBRETRO=OFF",
                    "-DBUILD_SHARED=OFF",
                    "-DBUILD_STATIC=ON",
                    "-DBUILD_TEST=OFF",
                    "-DBUILD_PERF=OFF",
                    "-DBUILD_SUITE=OFF",
                    "-DDISABLE_FRONTENDS=ON",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.anthropic.java)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    // Real org.json on the JVM unit-test classpath so JSON validators
    // (e.g. SentenceCorpusTest) can run without an Android device.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
