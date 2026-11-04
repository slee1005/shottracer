import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val billingKeyFile=rootProject.file("../../private/play-billing-public-key.txt")
val playBillingPublicKey=providers.gradleProperty("shottracerPlayPublicKey").orElse(providers.provider { if(billingKeyFile.isFile) billingKeyFile.readText() else "" }).get().filterNot { it.isWhitespace() }
val signingFile=rootProject.file("../../private/android-upload.properties")
val uploadProperties=Properties().apply { if(signingFile.isFile) signingFile.inputStream().use { load(it) } }
val signingReady=listOf("storeFile","storePassword","keyAlias","keyPassword").all { !uploadProperties.getProperty(it).isNullOrBlank() }
android {
    namespace = "com.ajc.shottracer"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.ajc.shottracer"
        minSdk = 34 // Android 14; independent of store target requirements.
        targetSdk = 36 // Google Play new-app submission baseline, checked 2026-11-03.
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", "\"" + playBillingPublicKey.replace("\"", "") + "\"")
        buildConfigField("boolean", "TEST_CODES", "false")
    }
    signingConfigs {
        create("upload") {
            if(signingReady) {
                val path=File(uploadProperties.getProperty("storeFile"))
                storeFile=if(path.isAbsolute) path else File(signingFile.parentFile,path.path)
                storeType=uploadProperties.getProperty("storeType","PKCS12")
                storePassword=uploadProperties.getProperty("storePassword")
                keyAlias=uploadProperties.getProperty("keyAlias")
                keyPassword=uploadProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") { if(signingReady) signingConfig=signingConfigs.getByName("upload") }
        getByName("debug") { applicationIdSuffix=".dev"; versionNameSuffix="-dev" }
        create("preview") { initWith(getByName("debug")); applicationIdSuffix=".dev.tester"; versionNameSuffix="-tester"; matchingFallbacks += "debug"; buildConfigField("boolean", "TEST_CODES", "true") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.android.billingclient:billing:8.3.0")
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

// An unconfigured release could take a store purchase but never verify it.
val verifyReleaseSubscription=tasks.register("verifyReleaseSubscription") {
    doLast {
        require(playBillingPublicKey.isNotBlank()) { "Configure -PshottracerPlayPublicKey with the app's Play licensing public key before a release build." }
        val bytes=Base64.getDecoder().decode(playBillingPublicKey)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }
}
tasks.matching { it.name=="preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSubscription) }

val verifyReleaseSigning=tasks.register("verifyReleaseSigning") {
    doLast { require(signingReady) { "Configure mobile-port/private/android-upload.properties before building a signed Play bundle." } }
}
tasks.matching { it.name=="preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }
