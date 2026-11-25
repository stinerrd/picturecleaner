package de.drepp.picturecleaner.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val media = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"

    private val byDefault = SourceScope()

    @Test
    fun excludesLockedChatsAndSentInEveryWhatsAppSectionByDefault() {
        assertTrue(Sources.isExcluded("$media/WhatsApp Images/Private/IMG-1.jpg", byDefault))
        assertTrue(Sources.isExcluded("$media/WhatsApp Video/Private/VID-1.mp4", byDefault))
        assertTrue(Sources.isExcluded("$media/WhatsApp Video/Sent/VID-2.mp4", byDefault))
        assertTrue(Sources.isExcluded("$media/WhatsApp Animated Gifs/Sent/", byDefault))
        assertFalse(Sources.isExcluded("$media/WhatsApp Video/VID-3.mp4", byDefault))
        assertFalse(Sources.isExcluded("/storage/emulated/0/Pictures/Viber/Private_card.jpg", byDefault))
    }

    @Test
    fun includesLockedChatsAndSentIndependently() {
        val withPrivate = SourceScope(includePrivate = true)
        assertFalse(Sources.isExcluded("$media/WhatsApp Images/Private/IMG-1.jpg", withPrivate))
        assertTrue(Sources.isExcluded("$media/WhatsApp Images/Sent/IMG-2.jpg", withPrivate))

        val withSent = SourceScope(includeSent = true)
        assertFalse(Sources.isExcluded("$media/WhatsApp Images/Sent/IMG-2.jpg", withSent))
        assertTrue(Sources.isExcluded("$media/WhatsApp Images/Private/IMG-1.jpg", withSent))
    }

    @Test
    fun recognisesLockedChatFolders() {
        assertTrue(Sources.isPrivate("$media/WhatsApp Video/Private/VID-1.mp4"))
        assertFalse(Sources.isPrivate("$media/WhatsApp Video/Sent/VID-1.mp4"))
        assertFalse(Sources.isPrivate("/storage/emulated/0/Pictures/Viber/Private_card.jpg"))
    }

    @Test
    fun recognisesVideos() {
        assertTrue(Sources.isVideo("/x/VID-1.MP4"))
        assertFalse(Sources.isVideo("/x/IMG-1.jpg"))
    }
}
