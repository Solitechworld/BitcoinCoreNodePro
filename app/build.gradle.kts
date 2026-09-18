import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is read from keystore.properties, which is gitignored and
// never committed. If it's absent (a fresh clone, or CI without secrets), the
// release build falls back to unsigned rather than failing -- you can still
// assemble and inspect it, you just can't upload it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasSigningConfig = keystoreProps.getProperty("storeFile") != null

// --- optional advertising integration -------------------------------------
//
// The ad implementation and its identifiers are not in this repository. A
// machine that ships ads has app/src/ads/ and app/ads.properties (both
// gitignored); a clean checkout has neither and builds ad-free.
//
// Exactly one of src/ads/java and src/noads/java is added to the main source
// set below. They declare the same symbols, so adding both would be a
// duplicate-class error -- that is why this is an either/or and not two
// directories that merge.
val adsSourceDir = file("src/ads/java")
val hasAds = adsSourceDir.exists()
val adsProps = Properties().apply {
    val f = file("ads.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.solitech.bitcoincorenode"
    compileSdk = 36

    defaultConfig {
        // The store identity: "bitcoin.local.mobile" on Play, and the label
        // reads "Bitcoin Mobile". The code namespace above stays
        // com.solitech.bitcoincorenode — applicationId and namespace are
        // independent, and renaming the namespace would churn every file for
        // no user-visible gain.
        applicationId = "bitcoin.local.mobile"

        // API 28 (Android 9). Set by the native payload, not by the UI: Bitcoin
        // Core's random.cpp needs getrandom()/getentropy(), which bionic only
        // exposes from 28. See docs/02-BUILD-NATIVE.md section 5.
        minSdk = 28
        targetSdk = 36

        // versionCode must never repeat on Play, even if a release was
        // deleted — the number is burned the moment an upload is accepted.
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Must match ABIS in native/scripts/config.sh. armeabi-v7a is
            // deliberately absent: Play has required 64-bit since 2019, and a
            // 32-bit address space is a bad fit for a UTXO cache.
            abiFilters += listOf("arm64-v8a")   // x86_64 emulator build: re-add once 20/21 has run for it
        }

        vectorDrawables { useSupportLibrary = true }

        // Empty when there is no ad integration. The manifest entry that
        // consumes it is harmless with an empty value in a build that has no
        // ad SDK to read it.
        manifestPlaceholders["admobAppId"] = adsProps.getProperty("admobAppId") ?: ""
    }

    sourceSets["main"].java.srcDir(if (hasAds) "src/ads/java" else "src/noads/java")

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 (JAR signing) looks redundant for an APK on minSdk 28 —
                // but the AAB itself is JAR-signed, and Play's uploader
                // rejects bundles without it ("invalid signature"). Enabled
                // deliberately; do not "optimize" this away again.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // Lets you point a debug build at regtest/signet without touching release.
            buildConfigField("boolean", "ALLOW_INSECURE_RPC", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ALLOW_INSECURE_RPC", "false")
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    packaging {
        jniLibs {
            // CRITICAL, DO NOT CHANGE.
            //
            // useLegacyPackaging = true sets android:extractNativeLibs="true",
            // which makes the installer write real files into nativeLibraryDir.
            // We exec() libbitcoind.so from that directory. With uncompressed
            // packaging (the modern default) the binary stays mapped inside the
            // APK with no filesystem path to exec, and the node cannot start.
            //
            // The cost is a slightly larger install footprint. That is the price
            // of running a real node process.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency")   // versions are pinned on purpose
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // NOTE: generateLocaleConfig is deliberately NOT enabled. It makes AGP
    // synthesise a locale config from a res/resources.properties file, and this
    // project already ships a hand-written res/xml/locales_config.xml that the
    // manifest points at via android:localeConfig. Turning both on fails the
    // build with "No resources.properties file found"; the hand-written one is
    // the source of truth.
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Only on a machine that has the (gitignored) ad sources. A clean checkout
    // does not pull the Google Mobile Ads SDK at all -- not as a dependency,
    // not into the APK, and not into any data-collection surface.
    if (hasAds) implementation(libs.play.services.ads)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Fail fast and loudly if someone tries to assemble a release without having
// run the native build. Shipping an APK with no node in it would otherwise be
// a silent, confusing runtime failure on a user's device.
tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
    .configureEach {
        doFirst {
            // Must mirror defaultConfig.ndk.abiFilters exactly. arm64-v8a is
            // the only ABI the native build has produced; x86_64 (emulator
            // only — via ARM translation) has never been built, so requiring
            // it here would block every release on a payload that does not
            // exist. Play requires 64-bit, which arm64 satisfies.
            val abis = listOf("arm64-v8a")
            val missing = abis.filter { abi ->
                !file("src/main/jniLibs/$abi/libbitcoind.so").exists()
            }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    """
                    Native payload missing for: ${missing.joinToString()}

                    A release build must contain the Bitcoin Core binaries. Run:
                        cd native/scripts && ./build-all.sh
                    See docs/02-BUILD-NATIVE.md.
                    """.trimIndent()
                )
            }
        }
    }
