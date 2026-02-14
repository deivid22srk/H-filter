package com.hfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HostManagerTest {

    private val hostManager = HostManagerFake()

    @Test
    fun testParseHostLine() {
        assertEquals("google.com", hostManager.parseHostLine("127.0.0.1 google.com"))
        assertEquals("doubleclick.net", hostManager.parseHostLine("0.0.0.0 doubleclick.net"))
        assertEquals("ads.example.com", hostManager.parseHostLine("ads.example.com"))
        assertEquals("ads.example.com", hostManager.parseHostLine("||ads.example.com^"))
        assertNull(hostManager.parseHostLine("# This is a comment"))
        assertNull(hostManager.parseHostLine("! This is also a comment"))
    }

    // Helper class to test internal logic without Android Context
    class HostManagerFake {
        fun parseHostLine(line: String): String? {
            var trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
            val commentIndex = trimmed.indexOf('#')
            if (commentIndex != -1) trimmed = trimmed.substring(0, commentIndex).trim()
            val parts = trimmed.split(Regex("\\s+"))
            val domain = if (parts.size >= 2) {
                if (parts[0].matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) parts[1] else cleanAdblockDomain(parts[0])
            } else cleanAdblockDomain(parts[0])
            return if (domain.contains(".") && !domain.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) domain.lowercase() else null
        }
        private fun cleanAdblockDomain(input: String): String {
            var domain = input
            if (domain.startsWith("||")) domain = domain.substring(2)
            val caretIndex = domain.indexOf('^')
            if (caretIndex != -1) domain = domain.substring(0, caretIndex)
            val slashIndex = domain.indexOf('/')
            if (slashIndex != -1) domain = domain.substring(0, slashIndex)
            return domain
        }
    }
}
