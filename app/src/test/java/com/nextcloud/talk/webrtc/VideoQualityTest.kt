/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.CameraEnumerationAndroid.CaptureFormat
import org.webrtc.RtpParameters

class VideoQualityTest {

    private fun format(width: Int, height: Int, maxFramerate: Int) =
        CaptureFormat(width, height, 0, maxFramerate * 1000)

    @Test
    fun testKeysMatchTheOtherClients() {
        assertEquals(
            listOf("480x360", "640x480", "960x540", "1280x720", "1920x1080", "2560x1440", "3840x2160"),
            MaxVideoResolution.entries.map { it.key }
        )
        assertEquals("640x480", MaxVideoResolution.DEFAULT.key)
    }

    @Test
    fun testUnknownKeyFallsBackToDefault() {
        assertEquals(MaxVideoResolution.UHD, MaxVideoResolution.fromKey("3840x2160"))
        assertEquals(MaxVideoResolution.DEFAULT, MaxVideoResolution.fromKey(null))
        assertEquals(MaxVideoResolution.DEFAULT, MaxVideoResolution.fromKey(""))
        assertEquals(MaxVideoResolution.DEFAULT, MaxVideoResolution.fromKey("4096x2160"))
    }

    @Test
    fun testBitratesStayBelowTheLimitOfTheServer() {
        val serverLimitBps = 25_000_000
        assertTrue(MaxVideoResolution.entries.all { it.maxBitrateBps < serverLimitBps })
        assertEquals(16_000_000, MaxVideoResolution.UHD.maxBitrateBps)
    }

    @Test
    fun testCaptureSizeFitsTheAspectRatioOfTheOrientation() {
        assertEquals(640 to 480, MaxVideoResolution.STANDARD.captureSize(isPortrait = true))
        assertEquals(640 to 360, MaxVideoResolution.STANDARD.captureSize(isPortrait = false))
        assertEquals(2880 to 2160, MaxVideoResolution.UHD.captureSize(isPortrait = true))
        assertEquals(3840 to 2160, MaxVideoResolution.UHD.captureSize(isPortrait = false))
    }

    @Test
    fun testSelectCaptureUsesTheLargestFormatThatFits() {
        val formats = listOf(
            format(320, 240, 30),
            format(640, 480, 30),
            format(1024, 576, 30),
            format(1280, 720, 30),
            format(3840, 2160, 30)
        )

        assertEquals(VideoCaptureSettings(1280, 720, 30), VideoQuality.selectCapture(formats, 1280, 720))
        assertEquals(VideoCaptureSettings(640, 480, 30), VideoQuality.selectCapture(formats, 960, 540))
    }

    @Test
    fun testSelectCapturePrefersFormatsWithFullFramerate() {
        val formats = listOf(format(1920, 1080, 30), format(3840, 2160, 15))

        assertEquals(VideoCaptureSettings(1920, 1080, 30), VideoQuality.selectCapture(formats, 3840, 2160))
        assertEquals(
            VideoCaptureSettings(3840, 2160, 15),
            VideoQuality.selectCapture(listOf(format(3840, 2160, 15)), 3840, 2160)
        )
    }

    @Test
    fun testSelectCaptureLimitsTheFramerate() {
        assertEquals(
            VideoCaptureSettings(1280, 720, VideoQuality.MAX_CAPTURE_FRAMERATE),
            VideoQuality.selectCapture(listOf(format(1280, 720, 60)), 1280, 720)
        )
    }

    @Test
    fun testSelectCaptureWithoutFittingOrKnownFormats() {
        assertEquals(
            VideoCaptureSettings(640, 480, 30),
            VideoQuality.selectCapture(listOf(format(1280, 720, 30), format(640, 480, 30)), 480, 270)
        )
        assertEquals(VideoCaptureSettings(640, 360, 30), VideoQuality.selectCapture(emptyList(), 640, 360))
    }

    @Test
    fun testLimitBitrateReportsChanges() {
        val encodings = listOf(RtpParameters.Encoding("h", true, 1.0), RtpParameters.Encoding("l", true, 2.0))
        encodings[1].maxBitrateBps = 900_000

        assertTrue(VideoQuality.limitBitrate(encodings, 900_000))
        assertEquals(listOf(900_000, 900_000), encodings.map { it.maxBitrateBps })
        assertFalse(VideoQuality.limitBitrate(encodings, 900_000))
    }
}
