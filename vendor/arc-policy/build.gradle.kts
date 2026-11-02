plugins { kotlin("jvm") version "2.3.0" }
repositories { mavenCentral() }
dependencies {
    compileOnly("com.google.android:android:4.1.1.4")
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../arc/app/src/main/java")
        kotlin.include("com/nuvio/tv/ui/screens/player/AutoSpeedupPolicy.kt")
        kotlin.include("com/nuvio/tv/core/player/thumbnail/PlaybackLine.kt")
        kotlin.include("com/nuvio/tv/ui/screens/settings/ConnectionsChoice.kt")
    }
    test {
        kotlin.srcDir("../arc/app/src/test/java")
        kotlin.include("com/nuvio/tv/ui/screens/player/AutoSpeedupPolicyTest.kt")
        kotlin.include("com/nuvio/tv/ui/screens/settings/ConnectionsChoiceTest.kt")
    }
}
tasks.test { testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
