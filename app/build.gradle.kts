import java.util.Properties
import java.time.LocalDate
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.devtools.ksp")
}

// Release credentials stay outside Git and the public publishing materials.
val releasePropertiesFile = rootProject.file("publishing/private/keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseProperties.getProperty(it).isNullOrBlank() }
val publisherFile = rootProject.file("publishing/private/publisher.properties")
val publisher = Properties().apply {
    if (publisherFile.isFile) publisherFile.reader(Charsets.UTF_8).use { load(it) }
}
val publisherName = publisher.getProperty("developerName", "").trim()
val policyDate = publisher.getProperty("effectiveDate", "").trim()
val displayedPolicyDate = if (policyDate == "FIRST_RELEASE") "首次正式发布之日（以 vivo 商店首次上架记录为准）" else policyDate
val policyReady = publisherName.isNotBlank() &&
    publisherName.none { it == '\n' || it == '\r' || it == '【' || it == '】' } && (policyDate == "FIRST_RELEASE" || runCatching {
    LocalDate.parse(policyDate)
}.isSuccess) && publisher.getProperty("policyApproved") == "true"
val policyTemplate = rootProject.file("publishing/vivo/02-隐私政策.md")
val policyText = policyTemplate.readText(Charsets.UTF_8).let { draft ->
    if (policyReady) draft.substringBefore("\n---")
        .replace("（待定稿）", "")
        .replace(Regex("(?m)^开发者：.*$")) { "开发者：$publisherName" }
        .replace("【上线前确认】", displayedPolicyDate) else draft
}
val policyVersion = MessageDigest.getInstance("SHA-256")
    .digest(policyText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
val policyAssets = layout.buildDirectory.dir("generated/privacyAssets")
val generatePrivacyAssets by tasks.registering {
    inputs.file(policyTemplate)
    inputs.property("policyText", policyText)
    outputs.dir(policyAssets)
    doLast {
        policyAssets.get().file("privacy-policy.txt").asFile.apply {
            parentFile.mkdirs()
            writeText(policyText.replace(Regex("(?m)^#+ "), ""), Charsets.UTF_8)
        }
    }
}
val validatePublishingSetup by tasks.registering {
    doLast {
        check(policyReady) { "Privacy policy is still a draft; complete and approve publishing/private/publisher.properties." }
        check(releaseSigningReady) { "Release signing credentials are missing." }
        check(rootProject.file(releaseProperties.getProperty("storeFile")).isFile) { "Release keystore file is missing." }
    }
}

android {
    namespace = "com.dcimgallery.raw"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.dcimgallery.raw"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "0.9.14"
        buildConfigField("boolean", "PRIVACY_POLICY_READY", policyReady.toString())
        buildConfigField("String", "PRIVACY_POLICY_VERSION", "\"$policyVersion\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        if (releaseSigningReady) create("release") {
            storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(policyAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
    dependsOn(validatePublishingSetup)
    doFirst {
        check(policyReady) {
            "Privacy policy is still a draft. Complete and approve publishing/private/publisher.properties."
        }
        check(releaseSigningReady) {
            "Release signing is missing. Configure publishing/private/keystore.properties; see publishing/vivo/07-正式包构建.md."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generatePrivacyAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
