plugins {
    `kotlin-dsl`
}

group = "dev.skip"
version = "0.1.0-SNAPSHOT"

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
