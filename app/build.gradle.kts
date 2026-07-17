import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.versions)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.easylauncher)
    alias(libs.plugins.spotless)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kover)
}

android {
    namespace = "org.connectbot"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.digitalpals.portal.android"
        versionCode = 2
        versionName = "0.1.1"

        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()

        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters.addAll(listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a"))
            debugSymbolLevel = "full"
        }

        testApplicationId = "com.digitalpals.portal.android.tests"
        testInstrumentationRunner = "org.connectbot.HiltTestRunner"

        // The following argument makes the Android Test Orchestrator run its
        // "pm clear" command after each test invocation. This command ensures
        // that the app's state is completely cleared between tests.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"

        multiDexEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (project.hasProperty("keystorePassword")) {
            create("release") {
                storeFile = file(property("keystoreFile") as String)
                storePassword = property("keystorePassword") as String
                keyAlias = property("keystoreAlias") as String
                keyPassword = property("keystorePassword") as String
            }
        }
    }

    buildTypes {
        release {
            manifestPlaceholders["memtagMode"] = "async"
            isShrinkResources = true
            isMinifyEnabled = true

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-tests.cfg")

            if (project.hasProperty("keystorePassword")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            manifestPlaceholders["memtagMode"] = "sync"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-debug.cfg")
            testProguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg", "proguard-tests.cfg")

            applicationIdSuffix = ".debug"
            enableAndroidTestCoverage = true
        }
    }

    flavorDimensions.add("license")

    productFlavors {
        // This product flavor uses the Conscrypt library which is open
        // source and licensed under Apache 2.
        create("oss") {
            dimension = "license"
            versionNameSuffix = "-oss"
            // No Google Play Services available for downloadable fonts
            buildConfigField("Boolean", "HAS_DOWNLOADABLE_FONTS", "false")
        }

        // This product flavor uses the Google Play Services library for
        // ProviderInstaller. It uses Conscrypt under-the-hood, but the
        // Google Play Services SDK itself is not open source.
        create("google") {
            dimension = "license"
            versionNameSuffix = ""
            // Google Play Services available for downloadable fonts
            buildConfigField("Boolean", "HAS_DOWNLOADABLE_FONTS", "true")
        }
    }

    testOptions {
        execution = "ANDROID_TEST_ORCHESTRATOR"
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test") {
            kotlin.srcDir("src/sharedTest/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDir("src/sharedTest/kotlin")
        }
    }

    lint {
        abortOnError = false
        lintConfig = file("lint.xml")
        checkTestSources = false
    }

    packaging {
        resources.excludes.add("META-INF/LICENSE.txt")
        resources.excludes.add("LICENSE.txt")
        resources.excludes.add("**/*.gwt.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kover {
    reports {
        filters {
            excludes {
                // Hilt/Dagger generated code
                packages("dagger.*", "hilt_aggregated_deps")
                classes(
                    "*_MembersInjector",
                    "*_MembersInjector\$*",
                    "*_Factory",
                    "*_Factory\$*",
                    "*Hilt_*",
                    "*_GeneratedInjector",
                    "*_HiltModules*",
                    "*_HiltComponents*",
                    "*Module_Provide*",
                    "*Module_Bind*",
                )
                // Build config
                classes("*.BuildConfig")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        checks.put("InvalidInlineTag", CheckSeverity.OFF)
        checks.put("AlmostJavadoc", CheckSeverity.OFF)
        checks.put("EmptyBlockTag", CheckSeverity.OFF)
        checks.put("MissingSummary", CheckSeverity.OFF)
        checks.put("ClassCanBeStatic", CheckSeverity.OFF)
        checks.put("ClassNewInstance", CheckSeverity.OFF)
        checks.put("DefaultCharset", CheckSeverity.OFF)
        checks.put("SynchronizeOnNonFinalField", CheckSeverity.OFF)
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Do not want any release candidates for updates.
tasks.withType<DependencyUpdatesTask>().configureEach {
    revision = "release"
    checkForGradleUpdate = false
    outputFormatter = "json"

    // Android apparently marks their "alpha" as "release" so we have to reject them.
    resolutionStrategy {
        componentSelection {
            all(
                Action<ComponentSelectionWithCurrent> {
                    val rejected =
                        listOf(
                            "alpha",
                            "beta",
                            "rc",
                            "cr",
                            "m",
                            "preview",
                        ).any { qualifier ->
                            candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-]*"))
                        }
                    if (rejected) {
                        reject("Release candidate")
                    }
                },
            )
        }
    }
}

dependencies {
    implementation(libs.termlib)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.material)
    implementation(libs.timber)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)

    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.tink)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestUtil(libs.androidx.test.orchestrator)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    errorprone(libs.errorprone.core)
}
