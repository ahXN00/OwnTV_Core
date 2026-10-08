pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A libmpv built on this PC (OwnTV_libmpv's buildscripts/wsl_build.sh), only while
        // the build command carries -Powntv.libmpvLocalRepo=<folder> (never a file) — :player-core then asks for
        // version "local". Both apps carry the same lines, as they resolve libmpv themselves.
        providers.gradleProperty("owntv.libmpvLocalRepo").orNull?.let { dir ->
            maven {
                name = "LocalLibmpv"
                url = uri(file(dir))
                content { includeVersion("tv.own.owntv", "libmpv", "local") }
            }
        }
        google()
        mavenCentral()
        // OwnTV's own Maven repository (this repo's gh-pages branch) — public, no login. Here for
        // tv.own.owntv:libmpv, the mpv engine built by ahXN00/OwnTV_libmpv.
        maven {
            name = "OwnTV"
            url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
            content { includeGroup("tv.own.owntv") }
        }
    }
}

rootProject.name = "OwnTVCore"
// Shared engine: data, sync, parsers, Room, backup, EPG, and every user-visible string. No UI
// framework beyond Compose runtime, so the same module backs the TV app and the mobile app.
include(":core")
// Playback engine: libmpv + the Media3/ExoPlayer handoff, the fallback ladder, watchdogs and the
// stream diagnostics. Depends on :core; renders nothing, so each app supplies its own HUD.
include(":player-core")
