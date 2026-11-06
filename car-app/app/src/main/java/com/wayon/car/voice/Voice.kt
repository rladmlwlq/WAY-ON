package com.wayon.car.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wayon.car.BuildConfig
import com.wayon.car.Config
import com.wayon.car.car.CarSignals
import java.util.Locale

/**
 * 음성 안내 (car-app-design.md 6-9). 동승자가 없을 때 에너지·주차장·휴게소 접근을 한 번씩 말한다.
 * 언제 말할지는 부르는 쪽이 정하고, 여기는 말하기만 한다.
 *
 * - Pleos TTS `ON_DEVICE` (네트워크·앱 등록 필요 없음). 순서: 생성 → initialize → addEventListener → setLocale → speak.
 *   addEventListener를 먼저 부르면 앱이 죽는다 (10장 에뮬레이터 결과).
 * - 말하기 전에 오디오 포커스를 잠깐 받고(내비 안내는 작아짐), [Config.VOICE_LEAD_SILENCE_MS]만큼 쉬고 말한다.
 * - 초기화 전에 온 문장은 마지막 1개만 기억했다가 준비되면 말한다.
 * - TTS를 못 쓸 때 녹음 음원을 트는 대비책은 아직 없다 (음원 파일 필요).
 */
class Voice(context: Context) {

    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = releaseFocus()
        override fun onStop() = releaseFocus()
        override fun onError(errorType: ErrorType) {
            log("음성 오류: $errorType")
            releaseFocus()
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(events)
                tts.setLocale(Locale.KOREA)
                ready = true
                log("음성 준비됨")
                pending?.let { pending = null; say(it) }
            }

            override fun onFailure(errorType: ErrorType) {
                log("음성 초기화 실패: $errorType")
            }
        })
    }

    fun say(text: String) {
        if (!ready) {
            pending = text
            return
        }
        audio.requestAudioFocus(focus)
        main.postDelayed({
            runCatching { tts.speak(text) }.onFailure {
                log("말하기 실패: ${it.message}")
                releaseFocus()
            }
        }, Config.VOICE_LEAD_SILENCE_MS)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        runCatching { tts.removeEventListener(events) }
        runCatching { tts.release() }
        releaseFocus()
        ready = false
    }

    private fun releaseFocus() {
        audio.abandonAudioFocusRequest(focus)
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
    }
}
