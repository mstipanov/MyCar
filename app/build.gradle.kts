import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing. The keystore and its passwords live outside version control; they are read
// from the gitignored `keystore.properties` at the repo root. If that file is absent — a clean
// checkout by a contributor, say — release builds are simply left unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.mycar"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mycar"
        minSdk = 28
        targetSdk = 36
        versionCode = 32
        versionName = "1.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Stable channel by default; the beta build type overrides this.
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://mycar.sting.hr\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Test channel. Outputs to its own directory (app/build/outputs/apk/beta) so a beta APK
        // can never be picked up as the stable release, and the "-betaN" suffix makes it obvious
        // which build a tester is running. initWith(debug) keeps the debug build's extras, but the
        // signing config is overridden to the release key below, so a beta installs over the stable
        // build (same package, same signature).
        create("beta") {
            initWith(getByName("debug"))
            versionNameSuffix = "-beta1"
            buildConfigField("String", "UPDATE_BASE_URL", "\"https://mycar-beta.sting.hr\"")
            // Sign beta with the release key too, so a beta installs over the stable app (and vice
            // versa): they share a package name, so they must share a signature.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Real release signing, so published APKs are not debug-signed.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Name the published APK MyCar-<version>.apk instead of app-debug.apk.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("MyCar-${output.versionName.get()}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Android Auto side: CarAppService + NavigationTemplate + the raw map surface.
    implementation(libs.androidx.car.app)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
