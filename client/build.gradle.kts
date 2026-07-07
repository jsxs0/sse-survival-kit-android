// The kit core is a plain JVM module on purpose: okhttp + coroutines, no
// Android dependency. The one Android-specific piece (turning
// ConnectivityManager callbacks into the networkBackSignals Flow) is injected
// by the app; see README for the wiring snippet.
plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.4.0")
    api("com.squareup.okhttp3:okhttp-sse:5.4.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
