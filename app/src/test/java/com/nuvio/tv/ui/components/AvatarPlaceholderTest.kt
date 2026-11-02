package com.nuvio.tv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarPlaceholderTest {
    @Test fun aProfileWithoutAPictureShowsThePlaceholder() {
        assertTrue(avatarShowsPlaceholder(null, imageLoaded = false))
        assertTrue(avatarShowsPlaceholder("", imageLoaded = false))
        assertTrue(avatarShowsPlaceholder("  ", imageLoaded = true))
    }

    @Test fun thePlaceholderStaysWhileThePictureLoadsOrAfterItFails() {
        assertTrue(avatarShowsPlaceholder("file:///android_asset/party-avatars/animals/whale.svg", imageLoaded = false))
        assertTrue(avatarShowsPlaceholder("https://example.com/avatar.png", imageLoaded = false))
    }

    @Test fun aLoadedPictureNeverHasThePlaceholderBehindIt() {
        assertFalse(avatarShowsPlaceholder("file:///android_asset/party-avatars/animals/whale.svg", imageLoaded = true))
        assertFalse(avatarShowsPlaceholder("https://example.com/avatar.png", imageLoaded = true))
    }
}
