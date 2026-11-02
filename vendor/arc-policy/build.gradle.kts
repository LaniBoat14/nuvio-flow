plugins { kotlin("jvm") version "2.3.0" }
repositories { mavenCentral() }
dependencies {
    compileOnly("com.google.android:android:4.1.1.4")
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/arcConstants"))
        kotlin.srcDir(layout.buildDirectory.dir("generated/arcPure"))
        kotlin.include("com/nuvio/tv/ui/screens/player/AutoSpeedupPolicy.kt")
        kotlin.include("com/nuvio/tv/ui/screens/player/FilmRate.kt")
        kotlin.include("com/nuvio/tv/ui/screens/player/AutoSpeedup.kt")
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

// Isolated policy harness only: extract the two compile-time constants verbatim
// from the pinned original object; the application port uses the complete object.
val extractArcPolicyConstants by tasks.registering {
    val source = file("../arc/app/src/main/java/com/nuvio/tv/ui/screens/player/AutoSpeedup.kt")
    inputs.file(source)
    val output = layout.buildDirectory.file("generated/arcConstants/com/nuvio/tv/ui/screens/player/AutoSpeedup.kt")
    outputs.file(output)
    doLast {
        val text = source.readText()
        val declarations = listOf("MB", "MAX_IN_FLIGHT", "MARGIN_BYTES").map { name ->
            text.lineSequence().first { it.contains("const val $name =") }.trim().removePrefix("private ")
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package com.nuvio.tv.ui.screens.player\ninternal object AutoSpeedup {\n" + declarations.joinToString("\n") + "\n}\n")
        }
    }
}
tasks.named("compileKotlin") { dependsOn(extractArcPolicyConstants) }

val stageArcPolicySources by tasks.registering(Copy::class) {
    from("../arc/app/src/main/java")
    include("com/nuvio/tv/ui/screens/player/AutoSpeedupPolicy.kt", "com/nuvio/tv/ui/screens/player/FilmRate.kt", "com/nuvio/tv/ui/screens/settings/ConnectionsChoice.kt", "com/nuvio/tv/core/player/thumbnail/PlaybackLine.kt")
    into(layout.buildDirectory.dir("generated/arcPure"))
}
tasks.named("compileKotlin") { dependsOn(stageArcPolicySources) }
