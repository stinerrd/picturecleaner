package de.drepp.picturecleaner.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val media = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"

    @Test
    fun excludesLockedChatsAndSentInEveryWhatsAppSection() {
        assertTrue(Sources.isExcluded("$media/WhatsApp Images/Private/IMG-1.jpg"))
        assertTrue(Sources.isExcluded("$media/WhatsApp Video/Private/VID-1.mp4"))
        assertTrue(Sources.isExcluded("$media/WhatsApp Video/Sent/VID-2.mp4"))
        assertTrue(Sources.isExcluded("$media/WhatsApp Animated Gifs/Sent/"))
        assertFalse(Sources.isExcluded("$media/WhatsApp Video/VID-3.mp4"))
        assertFalse(Sources.isExcluded("/storage/emulated/0/Pictures/Viber/Private_card.jpg"))
    }

    @Test
    fun recognisesVideos() {
        assertTrue(Sources.isVideo("/x/VID-1.MP4"))
        assertFalse(Sources.isVideo("/x/IMG-1.jpg"))
    }
}
