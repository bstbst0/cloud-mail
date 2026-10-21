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

// Simulator entitlements belong in Mach-O sections, not the host macOS signature.
// https://youtrack.jetbrains.com/issue/KT-61470
val simulatorTestEntitlements = layout.projectDirectory.file("src/iosTest/keychain-test.entitlements")
val simulatorTestDer = layout.buildDirectory.file("ios-test-identity/keychain-test.der")
val encodeSimulatorTestEntitlements = tasks.register<Exec>("encodeSimulatorTestEntitlements") {
    inputs.file(simulatorTestEntitlements)
    outputs.file(simulatorTestDer)
    doFirst { simulatorTestDer.get().asFile.parentFile.mkdirs() }
    commandLine("python3", rootProject.file("scripts/run-with-timeout.py").absolutePath, "60",
        "/usr/bin/derq", "query", "-f", "xml", "-i", simulatorTestEntitlements.asFile.absolutePath,
        "-o", simulatorTestDer.get().asFile.absolutePath, "--raw")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64 { binaries.framework { baseName = "CloudMail"; isStatic = true } }
    listOf(
        iosX64 { binaries.framework { baseName = "CloudMail"; isStatic = true } },
        iosSimulatorArm64 { binaries.framework { baseName = "CloudMail"; isStatic = true } }
    ).forEach { simulator ->
        simulator.testRuns.configureEach {
            executionSource.binary.linkerOpts(
                "-sectcreate", "__TEXT", "__entitlements", simulatorTestEntitlements.asFile.absolutePath,
                "-sectcreate", "__TEXT", "__ents_der", simulatorTestDer.get().asFile.absolutePath
            )
            executionSource.binary.linkTaskProvider.configure {
                dependsOn(encodeSimulatorTestEntitlements)
                inputs.file(simulatorTestEntitlements)
                inputs.file(simulatorTestDer)
            }
        }
    }
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
        nativeDistributions {
            modules("java.sql", "java.naming", "jdk.unsupported", "jdk.crypto.ec")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Cloud Mail"
            packageVersion = "1.0.0"
            linux { iconFile.set(project.file("src/desktopMain/resources/cloud-mail.png")) }
            macOS { iconFile.set(project.file("../branding/cloud-mail.icns")) }
            windows {
                iconFile.set(project.file("../branding/cloud-mail.ico"))
                menu = true
                shortcut = true
                menuGroup = "Cloud Mail"
                perUserInstall = true
                upgradeUuid = "7f2ca8b2-f986-4962-a9ca-dd3ca51b7580"
            }
        }
    }
}

// Development HTTP is opt-in for Gradle launches; installers never inherit this bypass.
tasks.withType<JavaExec>().configureEach {
    if (name == "run" && allowLocalHttp.get()) {
        jvmArgs("-Dcloudmail.development=true", "-Dcloudmail.allowLocalHttp=true")
    }
}

// Keychain integration needs the booted simulator's security service. The
// Kotlin/Native standalone process mode has no usable Keychain bootstrap.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    testLogging { events("started", "passed", "failed", "skipped") }
    providers.environmentVariable("CLOUD_MAIL_IOS_SIMULATOR").orNull?.let { device.set(it) }
}
