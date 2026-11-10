plugins {
    id("com.android.library")
    `maven-publish`
}

group = "ai.bitmesh"
version = "1.0.0"

android {
    namespace = "ai.bitmesh"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // The only runtime dependency: suspend calls and Flow for streamed replies.
    // HTTP is HttpURLConnection and JSON is org.json, both built into Android.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Android's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20250517")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.bitmesh"
            artifactId = "bitmesh-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Live tests read their keys from the environment; never cache their result.
    outputs.upToDateWhen { false }
}
