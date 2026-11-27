package com.riderlink.intercom.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAudioMixerTest {

    private val n = 4
    private val mixer = GroupAudioMixer<String>(frameSamples = n, newBuffer = { JitterBuffer(frameSamples = n) })
    private val hostOut = ShortArray(n)
    private val seq = HashMap<String, Long>()

    private fun talk(rider: String, value: Int, frames: Int = 6) {
        repeat(frames) {
            val s = seq.getOrDefault(rider, 0L)
            mixer.onIncoming(rider, s, ShortArray(n) { value.toShort() }, n)
            seq[rider] = s + 1
        }
    }

    /** Ticks until the jitter buffers are primed; returns the sends of the first tick with audio. */
    private fun tickUntilAudio(riders: List<String>, mic: Int? = null): Map<String, Int> {
        repeat(5) {
            mic?.let { mixer.submitHostMic(ShortArray(n) { _ -> it.toShort() }) }
            val sent = HashMap<String, Int>()
            mixer.tick(riders, hostOut) { rider, pcm -> sent[rider] = pcm[0].toInt() }
            if (sent.isNotEmpty() || hostOut[0] != 0.toShort()) return sent
        }
        return emptyMap()
    }

    @Test
    fun theHostHearsEveryRider_andEachRiderHearsEveryoneButThemselves() {
        talk("ana", 1000)
        talk("ben", 2000)

        val sent = tickUntilAudio(listOf("ana", "ben"), mic = 500)

        assertEquals(3000, hostOut[0].toInt())
        assertEquals(2500, sent["ana"]) // Ben + Host
        assertEquals(1500, sent["ben"]) // Ana + Host
    }

    @Test
    fun aSilentRiderStillHearsTheOthers_andTheTalkerGetsNothingBack() {
        talk("ana", 1000)

        val sent = tickUntilAudio(listOf("ana", "ben"))

        assertEquals(mapOf("ben" to 1000), sent)
        assertEquals(1000, hostOut[0].toInt())
    }

    @Test
    fun onlyTheHostTalking_reachesEveryRider_andTheHostHearsNothing() {
        val sent = HashMap<String, Int>()
        mixer.submitHostMic(ShortArray(n) { 700 })
        val hostHasAudio = mixer.tick(listOf("ana", "ben"), hostOut) { r, pcm -> sent[r] = pcm[0].toInt() }

        assertFalse(hostHasAudio)
        assertEquals(mapOf("ana" to 700, "ben" to 700), sent)
        assertEquals(0, hostOut[0].toInt())
    }

    @Test
    fun aQuietGroupSendsNothing() {
        var sends = 0
        mixer.tick(listOf("ana", "ben"), hostOut) { _, _ -> sends++ }
        assertEquals(0, sends)
    }

    @Test
    fun aMicFrameIsUsedOnce() {
        mixer.submitHostMic(ShortArray(n) { 700 })
        var sends = 0
        mixer.tick(listOf("ana"), hostOut) { _, _ -> sends++ }
        mixer.tick(listOf("ana"), hostOut) { _, _ -> sends++ }
        assertEquals(1, sends)
    }

    @Test
    fun loudOverlapIsLimited_notWrappedAround() {
        talk("ana", 30_000)
        talk("ben", 30_000)

        tickUntilAudio(listOf("ana", "ben"))

        val heard = hostOut[0].toInt()
        assertTrue("expected a limited positive peak, got $heard", heard in GroupAudioMixer.LIMITER_KNEE..Short.MAX_VALUE.toInt())
    }

    @Test
    fun aRiderWhoLeftIsNoLongerMixed() {
        talk("ana", 1000)
        talk("ben", 2000)

        tickUntilAudio(listOf("ben"))

        assertEquals(2000, hostOut[0].toInt())
    }

    @Test
    fun limiterKeepsQuietSamplesUntouched_andIsSymmetric() {
        assertEquals(1234, GroupAudioMixer.limit(1234).toInt())
        assertEquals(-1234, GroupAudioMixer.limit(-1234).toInt())
        assertEquals(-GroupAudioMixer.limit(60_000).toInt(), GroupAudioMixer.limit(-60_000).toInt())
    }
}
