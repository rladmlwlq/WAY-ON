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
 * - 소리 종류는 내비 안내(`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, Pleos 오디오 포커스 가이드). SDK 기본값은
 *   `USAGE_MEDIA`라서 초기화 뒤 [attributes]를 TTS에 넘긴다. 포커스 요청과 실제 소리가 같은 종류여야 한다.
 * - 말하기 전에 오디오 포커스를 잠깐 받고(내비 안내는 작아짐), 거절되면(통화·긴급 안내 중) 말하지 않는다.
 *   말하는 중에 포커스를 잃으면 멈춘다. 작아지기(duck)는 시스템이 하므로 그대로 둔다.
 * - 끝 콜백이 안 와도 [Config.VOICE_MAX_MS] 뒤에는 포커스를 놓는다 (내비 소리가 계속 작아져 있지 않게).
 * - 앞 묵음은 [Config.VOICE_LEAD_SILENCE_MS]만큼 재생을 늦춰서 넣는다. Pleos 가이드는 음성 데이터 앞에 무음을
 *   붙이는 방식을 권하지만, SDK는 문장만 받고(`speak(text)`) 음성 데이터를 주지 않아 이 방식만 가능하다.
 * - 초기화 전에 온 문장은 마지막 1개만 기억했다가 준비되면 말한다.
 * - TTS를 못 쓸 때 녹음 음원을 트는 대비책은 아직 없다 (음원 파일 필요).
 */
class Voice(context: Context) {

    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                // 다른 소리(통화, 음성 명령 등)가 포커스를 가져감: 멈춘다 → onStop에서 포커스를 놓음
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    log("음성 포커스 잃음($change), 멈춤")
                    runCatching { tts.stop() }
                    releaseFocus()
                }
                else -> Unit
            }
        }, main)
        .build()

    /** 끝 콜백이 안 올 때를 대비해 포커스를 놓는다 */
    private val giveUp = Runnable {
        log("음성 끝 콜백 없음, 포커스 놓음")
        releaseFocus()
    }

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
                tts.setAudioAttributes(attributes)
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
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 통화·긴급 안내 중이면 거절되는 것이 정상 (Pleos 오디오 포커스 가이드). 이번 안내는 건너뛴다
            log("음성 포커스 거절, 건너뜀: $text")
            return
        }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, Config.VOICE_MAX_MS)
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
        main.removeCallbacks(giveUp)
        audio.abandonAudioFocusRequest(focus)
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
    }
}
