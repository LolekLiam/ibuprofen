import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ravijol1.ibuprofen"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ravijol1.ibuprofen"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "v1.2.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("canary") {
            initWith(getByName("release"))
            applicationIdSuffix = ".canary"
            resValue("string", "app_name", "Ibuprofen Canary")
            signingConfig = null
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }


    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

// Generate a channel-specific icon from the originals without changing their alpha.
abstract class GenerateCanaryIcons : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = inputDirectory.get().asFile
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        source.walkTopDown().filter { it.name == "ic_launcher.png" && it.parentFile.name.startsWith("drawable") }.forEach { file ->
            val original = requireNotNull(ImageIO.read(file)) { "Could not read launcher icon: $file" }
            val inverted = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until original.height) for (x in 0 until original.width) {
                inverted.setRGB(x, y, original.getRGB(x, y) xor 0x00FFFFFF)
            }
            val output = destination.resolve("${file.parentFile.name}/ic_launcher_canary.png")
            output.parentFile.mkdirs()
            check(ImageIO.write(inverted, "png", output))
        }
    }
}

val generateCanaryIcons = tasks.register<GenerateCanaryIcons>("generateCanaryIcons") {
    inputDirectory.set(layout.projectDirectory.dir("src/main/res"))
    outputDirectory.set(layout.buildDirectory.dir("generated/canary-icons/res"))
}

// Only the dedicated canary variant reads these overrides; ordinary builds stay unchanged.
androidComponents {
    onVariants(selector().withBuildType("canary")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateCanaryIcons, GenerateCanaryIcons::outputDirectory)
        val version = providers.gradleProperty("canaryVersion").orElse("v1.1.0-dev")
        val code = providers.gradleProperty("canaryVersionCode").map { it.toInt() }.orElse(1)
        variant.outputs.forEach { output ->
            output.versionName.set(version)
            output.versionCode.set(code)
        }
    }
}


dependencies {

    // WorkManager for background scheduling
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Networking and parsing
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-scalars:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.biometric:biometric:1.1.0")

    // Security for encrypted storage
    implementation("androidx.security:security-crypto:1.1.0")

    // Java time desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}