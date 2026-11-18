/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import androidx.annotation.StringRes
import com.nextcloud.talk.R
import org.webrtc.CameraEnumerationAndroid.CaptureFormat
import org.webrtc.RtpParameters
import kotlin.math.abs

/**
 * Highest video quality the camera sends in a call. Less is sent when the camera, the device or the network can not
 * keep up.
 *
 * [maxBitrateBps] matches the highest simulcast layer of the iOS client for the same resolution, which keeps the
 * publisher well below the 25 Mbps limit of the server.
 */
enum class MaxVideoResolution(val width: Int, val height: Int, val maxBitrateBps: Int, @StringRes val nameRes: Int) {
    LOW(width = 480, height = 360, maxBitrateBps = 900_000, nameRes = R.string.nc_video_quality_low),
    STANDARD(width = 640, height = 480, maxBitrateBps = 900_000, nameRes = R.string.nc_video_quality_standard),
    MEDIUM(width = 960, height = 540, maxBitrateBps = 900_000, nameRes = R.string.nc_video_quality_medium),
    HD(width = 1280, height = 720, maxBitrateBps = 2_000_000, nameRes = R.string.nc_video_quality_hd),
    FULL_HD(width = 1920, height = 1080, maxBitrateBps = 4_000_000, nameRes = R.string.nc_video_quality_full_hd),
    QHD(width = 2560, height = 1440, maxBitrateBps = 8_000_000, nameRes = R.string.nc_video_quality_qhd),
    UHD(width = 3840, height = 2160, maxBitrateBps = 16_000_000, nameRes = R.string.nc_video_quality_uhd);

    val key: String
        get() = "${width}x$height"

    /**
     * Largest capture size that fits into this resolution with the aspect ratio used for the orientation of the
     * device: 4:3 in portrait and 16:9 in landscape.
     */
    fun captureSize(isPortrait: Boolean): Pair<Int, Int> {
        val (ratioWidth, ratioHeight) = if (isPortrait) {
            RATIO_4_TO_3_WIDTH to RATIO_4_TO_3_HEIGHT
        } else {
            RATIO_16_TO_9_WIDTH to RATIO_16_TO_9_HEIGHT
        }
        return if (width * ratioHeight >= height * ratioWidth) {
            height * ratioWidth / ratioHeight to height
        } else {
            width to width * ratioHeight / ratioWidth
        }
    }

    companion object {
        val DEFAULT = STANDARD

        private const val RATIO_4_TO_3_WIDTH = 4
        private const val RATIO_4_TO_3_HEIGHT = 3
        private const val RATIO_16_TO_9_WIDTH = 16
        private const val RATIO_16_TO_9_HEIGHT = 9

        fun fromKey(key: String?): MaxVideoResolution = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class VideoCaptureSettings(val width: Int, val height: Int, val framerate: Int)

object VideoQuality {
    const val MAX_CAPTURE_FRAMERATE = 30

    // The frame rates of a CaptureFormat are multiplied by 1000
    private const val FRAMERATE_UNIT = 1000

    /**
     * Picks the camera format closest to the target size that does not exceed it, preferring formats that reach
     * [MAX_CAPTURE_FRAMERATE]. Without any fitting format the closest one is used, and without any known format the
     * target itself is requested.
     */
    fun selectCapture(formats: List<CaptureFormat>, targetWidth: Int, targetHeight: Int): VideoCaptureSettings {
        val fitting = formats.filter { it.width <= targetWidth && it.height <= targetHeight }
        val candidates = fitting
            .filter { it.framerate.max >= MAX_CAPTURE_FRAMERATE * FRAMERATE_UNIT }
            .ifEmpty { fitting }
            .ifEmpty { formats }
        val format = candidates.minByOrNull { abs(it.width - targetWidth) + abs(it.height - targetHeight) }
            ?: return VideoCaptureSettings(targetWidth, targetHeight, MAX_CAPTURE_FRAMERATE)
        val framerate = (format.framerate.max / FRAMERATE_UNIT).coerceIn(1, MAX_CAPTURE_FRAMERATE)
        return VideoCaptureSettings(format.width, format.height, framerate)
    }

    /**
     * Limits the bitrate of all the given encodings.
     *
     * @return whether any encoding was changed
     */
    @JvmStatic
    fun limitBitrate(encodings: List<RtpParameters.Encoding>, maxBitrateBps: Int): Boolean {
        var changed = false
        for (encoding in encodings) {
            if (encoding.maxBitrateBps != maxBitrateBps) {
                encoding.maxBitrateBps = maxBitrateBps
                changed = true
            }
        }
        return changed
    }
}
