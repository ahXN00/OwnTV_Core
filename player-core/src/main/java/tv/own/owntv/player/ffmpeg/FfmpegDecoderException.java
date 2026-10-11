/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * OwnTV: copied from androidx/media tag 1.11.1, libraries/decoder_ffmpeg (not on Maven). Changes:
 * package renamed so no other library can collide on it, and FfmpegLibrary loads OwnTV's
 * libowntvffmpeg.so (OwnTV_libmpv, built on the engine's own FFmpeg) instead of ffmpegJNI, without
 * the unused setLibraries(). The native side binds to these class names (ffmpeg_jni.cc in
 * OwnTV_libmpv). On a Media3 bump, compare with that tag's copies.
 */
package tv.own.owntv.player.ffmpeg;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when an FFmpeg decoder error occurs. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {

  /* package */ FfmpegDecoderException(String message) {
    super(message);
  }

  /* package */ FfmpegDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
