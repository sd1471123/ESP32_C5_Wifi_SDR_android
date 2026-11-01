package io.github.sd1471123.sdr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Result of the PackageInstaller session opened by [Updater.install]. The system may also ask
 * the user to confirm the install; that follow-up intent has to be launched from here.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.update_installed, Toast.LENGTH_SHORT).show()

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(
                    context,
                    context.getString(R.string.update_install_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
