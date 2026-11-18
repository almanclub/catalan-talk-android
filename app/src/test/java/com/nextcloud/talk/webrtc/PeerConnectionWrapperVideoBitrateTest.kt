/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.signaling.SignalingMessageSender
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class PeerConnectionWrapperVideoBitrateTest {

    // RtpParameters are otherwise only created by the native code
    private fun rtpParametersWith(vararg encodings: RtpParameters.Encoding): RtpParameters {
        val constructor = RtpParameters::class.java.getDeclaredConstructor(
            String::class.java,
            RtpParameters.DegradationPreference::class.java,
            RtpParameters.Rtcp::class.java,
            List::class.java,
            List::class.java,
            List::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            "the-transaction-id",
            null,
            null,
            emptyList<Any>(),
            encodings.toList(),
            emptyList<Any>()
        )
    }

    @Test
    fun testVideoBitrateIsLimitedOnceDescriptionIsSet() {
        val mockedPeerConnection = Mockito.mock(PeerConnection::class.java)
        val mockedPeerConnectionFactory = Mockito.mock(PeerConnectionFactory::class.java)
        Mockito.`when`(
            mockedPeerConnectionFactory.createPeerConnection(
                any(PeerConnection.RTCConfiguration::class.java),
                any(PeerConnection.Observer::class.java)
            )
        ).thenReturn(mockedPeerConnection)
        val mockedStatusDataChannel = Mockito.mock(DataChannel::class.java)
        Mockito.`when`(mockedPeerConnection.createDataChannel(eq("status"), any())).thenReturn(mockedStatusDataChannel)

        val videoParameters = rtpParametersWith(RtpParameters.Encoding(null, true, null))
        val videoSender = Mockito.mock(RtpSender::class.java)
        Mockito.`when`(videoSender.track()).thenReturn(Mockito.mock(VideoTrack::class.java))
        Mockito.`when`(videoSender.parameters).thenReturn(videoParameters)
        Mockito.`when`(videoSender.setParameters(any())).thenReturn(true)
        val audioSender = Mockito.mock(RtpSender::class.java)
        Mockito.`when`(audioSender.track()).thenReturn(Mockito.mock(AudioTrack::class.java))
        Mockito.`when`(mockedPeerConnection.senders).thenReturn(listOf(videoSender, audioSender))
        Mockito.`when`(mockedPeerConnection.localDescription)
            .thenReturn(SessionDescription(SessionDescription.Type.OFFER, "the-sdp"))

        PeerConnectionWrapper(
            mockedPeerConnectionFactory,
            ArrayList(),
            MediaConstraints(),
            "the-session-id",
            "the-local-session-id",
            null,
            true,
            true,
            "video",
            Mockito.mock(SignalingMessageReceiver::class.java),
            Mockito.mock(SignalingMessageSender::class.java),
            false,
            null,
            null,
            2_000_000
        )
        val sdpObserverCaptor: ArgumentCaptor<SdpObserver> = ArgumentCaptor.forClass(SdpObserver::class.java)
        verify(mockedPeerConnection).createOffer(sdpObserverCaptor.capture(), any())

        sdpObserverCaptor.value.onSetSuccess()

        verify(videoSender).setParameters(videoParameters)
        assertEquals(2_000_000, videoParameters.encodings[0].maxBitrateBps)
        verify(audioSender, never()).parameters
        verify(audioSender, never()).setParameters(any())
    }
}
