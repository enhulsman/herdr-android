package dev.herdr.android.push

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.herdr.android.HerdrApp
import dev.herdr.android.MainActivity
import dev.herdr.android.ui.agents.AgentKey

/**
 * Where an agent notification's tap lands: hands the agent to the app
 * ([HerdrApp.alertTap]) and brings up [MainActivity], which opens its
 * terminal. Not exported, so only the app's own notifications (a PendingIntent
 * runs as the app) can start it: the exported launcher takes no terminal from
 * an intent, so another app cannot open or take over one (Codex review, M3).
 * Shows nothing and finishes at once.
 */
class AlertTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val machine = intent?.getStringExtra(EXTRA_MACHINE)
        val terminal = intent?.getStringExtra(EXTRA_TERMINAL)
        if (machine != null && terminal != null) {
            (application as HerdrApp).alertTap.value = AgentKey(machine, terminal)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        const val EXTRA_MACHINE = "machine"
        const val EXTRA_TERMINAL = "terminal"

        fun intent(context: Context, key: AgentKey): Intent =
            Intent(context, AlertTapActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MACHINE, key.machine)
                .putExtra(EXTRA_TERMINAL, key.address)
    }
}
