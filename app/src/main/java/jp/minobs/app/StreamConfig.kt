package jp.minobs.app

data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val rotation: Int,
    val audioMode: AudioMode
) {
    enum class AudioMode { MICROPHONE, INTERNAL, MIX }
}
