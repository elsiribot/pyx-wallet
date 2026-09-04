import java.util.Properties
import org.gradle.api.artifacts.dsl.LockMode

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pyxAndroidSdk = providers.gradleProperty("pyx.androidSdk").get().toInt()

// During the side-by-side migration, reuse the existing Android signing
// configuration without copying credentials into the native project.
val releasePropertiesFile = rootProject.file("../android/key.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.exists()) {
        releasePropertiesFile.inputStream().use(::load)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvm.get()))
    }
}

android {
    namespace = "cash.pyx.app"
    compileSdk = pyxAndroidSdk

    defaultConfig {
        applicationId = "cash.pyx.app"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = pyxAndroidSdk
        // Preserve the current Flutter release lineage for upgrade testing.
        versionCode = 38
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-test-rules.pro")

        ndk {
            abiFilters += "arm64-v8a"
            if (System.getenv("PYX_ABI_X86_64") == "1") {
                abiFilters += "x86_64"
            }
        }
    }

    signingConfigs {
        if (releasePropertiesFile.exists()) {
            create("release") {
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
                storePassword = releaseProperties.getProperty("storePassword")
                storeFile = rootProject.file("../android/${releaseProperties.getProperty("storeFile")}")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".nativepreview"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Installable, non-publishing twin of release for headless runtime
        // certification. It uses identical shrinking rules and native code but
        // cannot replace or read cash.pyx.app because its package ID differs.
        create("r8cert") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8cert"
            versionNameSuffix = "-r8cert"
            signingConfig = signingConfigs.getByName("debug")
            // Same debuggability/R8 behavior as release. The separately signed
            // test APK is allowed to instrument it because both use debug key.
            isDebuggable = false
            matchingFallbacks += listOf("release")
            // AndroidJUnitRunner executes in the target process and loads this
            // facade before tests begin. Release itself does not retain it.
            proguardFile("proguard-r8cert-rules.pro")
        }
    }

    // Normal device certification stays on debug. The dedicated headless tool
    // opts into r8cert with -PpyxTestBuildType=r8cert.
    testBuildType = providers.gradleProperty("pyxTestBuildType").orElse("debug").get()

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvm.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    "r8certImplementation"(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Vulnerability and license review must apply to the same resolved artifacts
// used by release builds. Keep the committed Gradle lock state authoritative;
// update it deliberately with --write-locks and review the resulting diff.
dependencyLocking {
    // CI and local builds must fail when a resolved component is absent from
    // the reviewed lock state. Gradle's DEFAULT mode can resolve an unlocked
    // component and only warn, which is too weak for the migration gate.
    lockMode.set(LockMode.STRICT)
    lockAllConfigurations()
}
