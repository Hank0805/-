from pathlib import Path
import re

p = Path('app/src/main/java/jp/minobs/app/ScreenStreamService.kt')
s = p.read_text()

# Imports
if 'import android.os.PowerManager' not in s:
    s = s.replace('import android.os.Looper\n', 'import android.os.Looper\nimport android.os.PowerManager\n')

# Audio fields
old = '''    private val audioEffect = StudioAudioEffect()
    private var micVolume = 1f
    private var internalVolume = 1f
    private var micMuted = false
    private var privacyMode = false
'''
new = '''    private val audioEffect = StudioAudioEffect()
    private var studioMixer: StudioMixAudioSource? = null
    private var micVolume = 1f
    private var internalVolume = 1f
    private var usbVolume = 1f
    private var micDelayMs = 0
    private var internalDelayMs = 0
    private var usbDelayMs = 0
    private var monitorMode = StudioMixAudioSource.MonitorMode.OFF
    private var monitorVolume = .65f
    private var micMuted = false
    private var privacyMode = false
'''
if old not in s:
    raise SystemExit('audio fields block not found')
s = s.replace(old, new, 1)

# Thermal fields
needle = '    private var lastBitrate = 0L\n'
if 'private var thermalStatus' not in s:
    s = s.replace(needle, needle + '''    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            val factor = if (status >= PowerManager.THERMAL_STATUS_CRITICAL) .55 else .72
            val protected = (config.bitrate * factor).toInt().coerceAtLeast(1_500_000)
            if (currentTargetBitrate > protected) {
                currentTargetBitrate = protected
                runCatching { stream?.setVideoBitrateOnFly(currentTargetBitrate) }
            }
            sendStatus("発熱保護: ${thermalLabel(status)} / ${currentTargetBitrate / 1000}kbps")
        } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
            sendStatus("端末温度: ${thermalLabel(status)}")
        }
    }
''', 1)

# onCreate register listener
old = '''    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
'''
new = '''    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            thermalStatus = power.currentThermalStatus
            runCatching { power.addThermalStatusListener(thermalListener) }
        }
'''
if old not in s:
    raise SystemExit('onCreate header not found')
s = s.replace(old, new, 1)

# audio source selection
old = '''        val audioSource = when (config.audioMode) {
            StreamConfig.AudioMode.MICROPHONE -> MicrophoneSource().also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.INTERNAL -> InternalAudioSource(projection).also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.MIX -> MixAudioSource(projection).also { it.setAudioEffect(audioEffect) }
        }
'''
new = '''        studioMixer = null
        val audioSource = when (config.audioMode) {
            StreamConfig.AudioMode.MICROPHONE -> MicrophoneSource().also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.INTERNAL -> InternalAudioSource(projection).also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.MIX -> StudioMixAudioSource(applicationContext, projection, audioEffect).also {
                studioMixer = it
                applyStudioMixerSettings(it)
            }
        }
'''
if old not in s:
    raise SystemExit('audio source selection not found')
s = s.replace(old, new, 1)

# set audio block
pattern = r'''    fun setAudioVolumes\(microphone: Float, internal: Float\) \{.*?    fun setAudioPreset\(name: String\) \{ audioEffect\.applyPreset\(name\); sendStatus\("Audio preset: \$name"\) \}\n'''
replacement = '''    private fun applyStudioMixerSettings(source: StudioMixAudioSource) {
        source.microphoneVolume = micVolume
        source.internalVolume = internalVolume
        source.usbVolume = usbVolume
        source.microphoneMuted = micMuted
        source.microphoneDelayMs = micDelayMs
        source.internalDelayMs = internalDelayMs
        source.usbDelayMs = usbDelayMs
        source.monitorMode = monitorMode
        source.monitorVolume = monitorVolume
    }

    fun setAudioVolumes(microphone: Float, internal: Float) {
        micVolume = microphone.coerceIn(0f, 2f)
        internalVolume = internal.coerceIn(0f, 2f)
        when (val source = stream?.audioSource) {
            is StudioMixAudioSource -> {
                source.microphoneVolume = micVolume
                source.internalVolume = internalVolume
            }
            is MixAudioSource -> { source.microphoneVolume = micVolume; source.internalVolume = internalVolume }
            is MicrophoneSource -> source.microphoneVolume = micVolume
            is InternalAudioSource -> source.internalVolume = internalVolume
        }
    }

    fun setUsbAudioVolume(value: Float) {
        usbVolume = value.coerceIn(0f, 2f)
        studioMixer?.usbVolume = usbVolume
        sendStatus("USB音声 ${(usbVolume * 100).toInt()}%")
    }

    fun setMicrophoneMuted(muted: Boolean) {
        micMuted = muted
        when (val source = stream?.audioSource) {
            is StudioMixAudioSource -> source.microphoneMuted = muted
            is MixAudioSource -> if (muted) source.mute() else source.unMute()
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
        }
    }

    fun setAudioDelays(microphoneMs: Int, internalMs: Int, usbMs: Int) {
        micDelayMs = microphoneMs.coerceIn(0, 2000)
        internalDelayMs = internalMs.coerceIn(0, 2000)
        usbDelayMs = usbMs.coerceIn(0, 2000)
        studioMixer?.let {
            it.microphoneDelayMs = micDelayMs
            it.internalDelayMs = internalDelayMs
            it.usbDelayMs = usbDelayMs
        }
        sendStatus("音ズレ補正 MIC ${micDelayMs}ms / 内部 ${internalDelayMs}ms / USB ${usbDelayMs}ms")
    }

    fun setAudioMonitor(mode: StudioMixAudioSource.MonitorMode, volume: Float = monitorVolume) {
        monitorMode = mode
        monitorVolume = volume.coerceIn(0f, 1f)
        studioMixer?.monitorMode = monitorMode
        studioMixer?.monitorVolume = monitorVolume
        sendStatus("音声モニター: ${monitorMode.name}")
    }

    fun audioMixerState(): JSONObject = JSONObject().apply {
        put("micVolume", micVolume)
        put("internalVolume", internalVolume)
        put("usbVolume", usbVolume)
        put("micDelayMs", micDelayMs)
        put("internalDelayMs", internalDelayMs)
        put("usbDelayMs", usbDelayMs)
        put("monitorMode", monitorMode.name)
        put("monitorVolume", monitorVolume)
        put("usbAudio", studioMixer?.hasUsbAudio() == true)
        put("filters", audioEffect.summary())
    }

    fun configureAudioFilters(
        gateEnabled: Boolean,
        gateThreshold: Int,
        compressorEnabled: Boolean,
        compressorThreshold: Int,
        compressorRatio: Float,
        lowGain: Float,
        midGain: Float,
        highGain: Float,
        reverbEnabled: Boolean,
        reverbWet: Float,
        reverbFeedback: Float,
        reverbDelayMs: Int,
        limiterEnabled: Boolean
    ) {
        audioEffect.gateEnabled = gateEnabled
        audioEffect.gateThreshold = gateThreshold.coerceIn(0, 5000)
        audioEffect.compressorEnabled = compressorEnabled
        audioEffect.compressorThreshold = compressorThreshold.coerceIn(1000, 30000)
        audioEffect.compressorRatio = compressorRatio.coerceIn(1f, 12f)
        audioEffect.lowGain = lowGain.coerceIn(.25f, 2.5f)
        audioEffect.midGain = midGain.coerceIn(.25f, 2.5f)
        audioEffect.highGain = highGain.coerceIn(.25f, 2.5f)
        audioEffect.reverbEnabled = reverbEnabled
        audioEffect.reverbWet = reverbWet.coerceIn(0f, .85f)
        audioEffect.reverbFeedback = reverbFeedback.coerceIn(0f, .85f)
        audioEffect.reverbDelayMs = reverbDelayMs.coerceIn(25, 600)
        audioEffect.limiterEnabled = limiterEnabled
        sendStatus("音声フィルター更新")
    }

    fun audioFilterState(): JSONObject = JSONObject().apply {
        put("gateEnabled", audioEffect.gateEnabled); put("gateThreshold", audioEffect.gateThreshold)
        put("compressorEnabled", audioEffect.compressorEnabled); put("compressorThreshold", audioEffect.compressorThreshold)
        put("compressorRatio", audioEffect.compressorRatio)
        put("lowGain", audioEffect.lowGain); put("midGain", audioEffect.midGain); put("highGain", audioEffect.highGain)
        put("reverbEnabled", audioEffect.reverbEnabled); put("reverbWet", audioEffect.reverbWet)
        put("reverbFeedback", audioEffect.reverbFeedback); put("reverbDelayMs", audioEffect.reverbDelayMs)
        put("limiterEnabled", audioEffect.limiterEnabled); put("summary", audioEffect.summary())
    }

    fun setAudioPreset(name: String) { audioEffect.applyPreset(name); sendStatus("Audio preset: $name") }
'''
s, n = re.subn(pattern, replacement, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'audio methods replace failed {n}')

# remote status additions
old = '''            "destinations" to lastEndpoints.size, "replay" to replay.status(), "droppedVideo" to droppedVideo, "droppedAudio" to droppedAudio,
            "remoteUrl" to remoteUrl()
'''
new = '''            "destinations" to lastEndpoints.size, "replay" to replay.status(), "droppedVideo" to droppedVideo, "droppedAudio" to droppedAudio,
            "remoteUrl" to remoteUrl(), "thermalStatus" to thermalStatus, "thermalLabel" to thermalLabel(thermalStatus),
            "audioFilters" to audioEffect.summary(), "usbAudio" to (studioMixer?.hasUsbAudio() == true),
            "micDelayMs" to micDelayMs, "internalDelayMs" to internalDelayMs, "usbDelayMs" to usbDelayMs,
            "monitorMode" to monitorMode.name
'''
if old not in s:
    raise SystemExit('remote status block not found')
s = s.replace(old, new, 1)

# release mixer
old = '''    private fun releaseEngine() {
        runCatching { stream?.release() }; stream = null
        val p = mediaProjection; mediaProjection = null; runCatching { p?.stop() }; captureReady = false
    }
'''
new = '''    private fun releaseEngine() {
        runCatching { stream?.release() }; stream = null
        studioMixer = null
        val p = mediaProjection; mediaProjection = null; runCatching { p?.stop() }; captureReady = false
    }
'''
if old not in s:
    raise SystemExit('releaseEngine not found')
s = s.replace(old, new, 1)

# thermal helper and remove listener
needle = '    private fun sceneLabel(scene: String) = when (scene) { "talk" -> "雑談"; "wait" -> "待機"; else -> "ゲーム" }\n'
if 'private fun thermalLabel' not in s:
    s = s.replace(needle, needle + '''
    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "軽度"
        PowerManager.THERMAL_STATUS_MODERATE -> "中程度"
        PowerManager.THERMAL_STATUS_SEVERE -> "高温"
        PowerManager.THERMAL_STATUS_CRITICAL -> "危険"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "緊急"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "停止寸前"
        else -> "不明"
    }
''', 1)

old = '''    override fun onDestroy() {
        remoteServer?.stop(); chatManager.stopAll(); autoScene.stop(); replay.stop(); hideFloatingControls(); stopOverlayCamera(); releaseEngine(); super.onDestroy()
    }
'''
new = '''    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            runCatching { power.removeThermalStatusListener(thermalListener) }
        }
        remoteServer?.stop(); chatManager.stopAll(); autoScene.stop(); replay.stop(); hideFloatingControls(); stopOverlayCamera(); releaseEngine(); super.onDestroy()
    }
'''
if old not in s:
    raise SystemExit('onDestroy block not found')
s = s.replace(old, new, 1)

p.write_text(s)
