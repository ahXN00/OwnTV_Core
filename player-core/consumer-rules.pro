# Keep rules that :player-core imposes on anything consuming it. The engine's other keep rules still
# live in each app's proguard-rules.pro.

# ExoPlayer's FFmpeg audio decoder (tv.own.owntv.player.ffmpeg). libowntvffmpeg.so binds its natives
# with RegisterNatives by class and method name and calls growOutputBuffer back from C++, so R8 must
# keep those names; renamed, JNI_OnLoad fails and the decoder silently reports unavailable.
-keep class tv.own.owntv.player.ffmpeg.FfmpegLibrary {
    native <methods>;
}
-keep class tv.own.owntv.player.ffmpeg.FfmpegAudioDecoder {
    native <methods>;
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
