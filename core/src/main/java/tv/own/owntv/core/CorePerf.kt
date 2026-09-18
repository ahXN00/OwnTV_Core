package tv.own.owntv.core

import android.util.Log

/**
 * One Logcat tag for the EPG performance trace, and one gate in front of it.
 *
 * The guide subsystem is about to be rewritten for speed, and every step of that work claims a win
 * that is only checkable against numbers from a real device — 500k programmes and several thousand
 * channels behave nothing like a test fixture. These lines are what produce those numbers.
 *
 * Maintainer builds only ([CoreBuildInfo.devTools]). On a user's device the flag is false and the
 * lambda is never invoked, so the cost is one static boolean read — the message, and every
 * `SystemClock` call and collection walk inside it, never happens. It is a lambda and not a string
 * parameter for exactly that reason. Note that the flag is assigned at runtime by the host app, so
 * unlike an app-side `BuildConfig` constant R8 cannot remove the branch outright; it does not need
 * to.
 *
 * Deliberately not a logging framework. It is a tag, a flag and a lambda, because that is the whole
 * requirement; the picker hook in [tv.own.owntv.core.live.LiveEpgReader] has been written this way
 * by hand since it was added, and this only stops the next one from inventing its own tag.
 */
object CorePerf {

    /** `adb logcat -s OwnTVPerf` gets the whole trace and nothing else. */
    const val TAG = "OwnTVPerf"

    /** Emits [message] under [TAG], but only in a maintainer build. */
    inline fun log(message: () -> String) {
        if (CoreBuildInfo.devTools) Log.i(TAG, message())
    }
}
