package io.embodit.xrayhand.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.xrayhand.platform.XrLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.xrayhand.DEBUG`); the
 * command list is in [DebugCommands] and AGENTS.md.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.xrayhand.DEBUG -p io.embodit.xrayhand --es cmd 'pose open/open'"
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        XrLog.i("CMD recv '$cmd'")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd)
    }
}
