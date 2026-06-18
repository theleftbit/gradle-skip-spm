plugins {
    `kotlin-dsl`
}

group = "dev.skip"
version = "0.1.0"

dependencies {
    // Applied on the consuming root project to register the afterSync trigger that keeps the
    // shared symbols resolved in Android Studio.
    implementation("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:1.4.1")
}

gradlePlugin {
    plugins {
        create("skipSpm") {
            id = "dev.skip.spm"
            implementationClass = "dev.skip.spm.SkipSpmPlugin"
            displayName = "Skip SPM → Android (Gradle)"
            description = "Build a Skip (SwiftPM) package into Android AARs via `skip export` " +
                "and consume them in the app build, with IDE symbol resolution on Gradle sync."
        }
    }
}
