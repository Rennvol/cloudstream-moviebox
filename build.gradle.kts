import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    // CloudStream gradle plugin, published from recloudstream/gradle via JitPack.
    // Only commit-sha coordinates resolve correctly; branch coordinates break
    // Gradle's module metadata check.
    val csGradlePluginVersion = "81b1d424d"

    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream.gradle:gradle:$csGradlePluginVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("https://github.com/Rennvol/cloudstream-moviebox")
        authors = listOf("Rennvol")
    }

    android {
        namespace = "com.rennv.moviebox"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            // com.lagradost:cloudstream3:pre-release is built for JVM 11.
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.16")
        implementation("org.jsoup:jsoup:1.22.1")
        implementation("androidx.annotation:annotation:1.9.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
