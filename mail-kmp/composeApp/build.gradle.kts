import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android)
    alias(libs.plugins.sqldelight)
}
val configuredServerUrl = providers.gradleProperty("cloudMailServerUrl").orElse("")
val allowLocalHttp = providers.gradleProperty("cloudMailAllowLocalHttp").map { it == "true" }.orElse(false)
fun quotedBuildString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64 { binaries.framework { baseName = "CloudMail"; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = "CloudMail"; isStatic = true } }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime); implementation(compose.foundation)
            implementation(compose.material3); implementation(compose.materialIconsExtended)
            implementation(compose.ui); implementation(libs.lifecycle)
            implementation(libs.ktor.core); implementation(libs.serialization); implementation(libs.coroutines)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test); implementation(libs.ktor.mock) }
        androidMain.dependencies { implementation(libs.androidx.core); implementation(libs.ktor.okhttp); implementation(libs.sqlite.android); implementation(libs.androidx.activity); implementation(libs.androidx.work) }
        iosMain.dependencies { implementation(libs.ktor.darwin); implementation(libs.sqlite.native) }
        val desktopTest by getting { dependencies { implementation(compose.desktop.uiTestJUnit4); implementation(compose.desktop.currentOs); implementation(libs.sqlite.desktop) } }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs); implementation(libs.ktor.cio); implementation(libs.sqlite.desktop); implementation(libs.jna.platform); implementation(libs.coroutines.swing) } }
    }
}
android {
    namespace = "com.cloudmail.app"
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "DEFAULT_SERVER_URL", quotedBuildString(configuredServerUrl.get()))
        buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "false")
        manifestPlaceholders["cloudMailLocalCleartext"] = "false"
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", allowLocalHttp.get().toString())
            manifestPlaceholders["cloudMailLocalCleartext"] = allowLocalHttp.get().toString()
        }
    }
    compileSdk = 35
    defaultConfig { applicationId = "com.cloudmail.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
sqldelight { databases { create("MailDatabase") { packageName.set("com.cloudmail.db") } } }
compose.desktop {
    application {
        mainClass = "com.cloudmail.app.MainKt"
        jvmArgs += "-Dcloudmail.defaultServerUrl=${configuredServerUrl.get()}"
        nativeDistributions { modules("java.sql", "java.naming", "jdk.unsupported", "jdk.crypto.ec"); targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb); packageName = "Cloud Mail"; packageVersion = "1.0.0" }
    }
}

// Development HTTP is opt-in for Gradle launches; installers never inherit this bypass.
tasks.withType<JavaExec>().configureEach {
    if (name == "run" && allowLocalHttp.get()) {
        jvmArgs("-Dcloudmail.development=true", "-Dcloudmail.allowLocalHttp=true")
    }
}
