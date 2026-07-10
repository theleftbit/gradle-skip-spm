plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "com.theleftbit"
version = "0.2.0"

dependencies {
    // Applied on the consuming root project to register the afterSync trigger that keeps the
    // shared symbols resolved in Android Studio.
    implementation("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:1.4.1")
}

gradlePlugin {
    website = "https://github.com/theleftbit/gradle-skip-spm"
    vcsUrl = "https://github.com/theleftbit/gradle-skip-spm.git"
    plugins {
        create("skipSpm") {
            id = "com.theleftbit.skipspm"
            implementationClass = "com.theleftbit.skipspm.SkipSpmPlugin"
            displayName = "Skip SPM → Android (Gradle)"
            description = "Build a Skip (SwiftPM) package into Android AARs via `skip export` " +
                "and consume them in the app build, with IDE symbol resolution on Gradle sync."
            tags = listOf("skip", "swift", "swiftpm", "android", "kmp", "aar")
        }
    }
}
