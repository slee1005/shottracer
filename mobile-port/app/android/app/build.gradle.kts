import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ajc.shottracer"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.ajc.shottracer.dev"
        minSdk = 34 // Android 14; independent of store target requirements.
        targetSdk = 35 // Development baseline. Recheck store policy before submission.
        versionCode = 1
        versionName = "0.0.1-dev"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("androidx.media3:media3-common:1.9.0")
}

// Only the build machine downloads the pinned model; the installed app works offline.
val poseAssets = layout.buildDirectory.dir("generated/poseAssets")
android.sourceSets.getByName("main").assets.srcDir(poseAssets)
val preparePoseModel by tasks.registering {
    val model = poseAssets.map { it.file("pose_landmarker_full.task") }
    outputs.file(model)
    doLast {
        val target = model.get().asFile
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val expected = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
        if (!target.isFile || hash(target) != expected) {
            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, "pose.part")
            URI("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task").toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(hash(temporary) == expected) { "Pose model SHA-256 mismatch" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
tasks.named("preBuild").configure { dependsOn(preparePoseModel) }
