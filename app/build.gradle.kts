plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CI is the source of the build number: GITHUB_RUN_NUMBER increments once per run of the
// release workflow, so versionCode never goes backwards. Local builds fall back to 1.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER")
    ?: providers.gradleProperty("buildNumber").orNull
    ?: "1").toInt()
val baseVersionName = providers.gradleProperty("baseVersionName").get()

// Release signing comes from the environment so the keystore never lives in the repo.
// Absent it, AGP falls back to an unsigned release APK (see .github/workflows/release.yml).
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

val serverSettingNames = listOf("TABLE_URL", "TABLE_API_KEY", "TABLE_TTL_SECONDS", "TABLE_TEST_FAULTS")

fun serverSetting(name: String): String? =
    providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull

android {
    namespace = "com.rainbowcockroach.table.tableandroidclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rainbowcockroach.table.tableandroidclient"
        // DESIGN §3 publishes downloads to MediaStore.Downloads, which is API 29+.
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "$baseVersionName.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The instrumented smoke test reaches the dev server through `adb reverse`, so it gets
        // the same settings the JVM suite does — as runner arguments, the only channel a test
        // on a device can read them from.
        testInstrumentationRunnerArguments.putAll(
            serverSettingNames.mapNotNull { name -> serverSetting(name)?.let { name to it } }.toMap()
        )
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

// The conformance suite in src/test talks to a real table-server; DESIGN §7 takes
// its address from the environment or a Gradle property and skips without one.
tasks.withType<Test>().configureEach {
    for (name in serverSettingNames) {
        val value = serverSetting(name)
        if (value != null) systemProperty(name, value)
    }
    // The server is an input Gradle cannot fingerprint, so a green run must never
    // let the next one be skipped as up-to-date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.ui.tooling)
}