package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nuvio.tv.R

object ExternalPlayerLauncher {

    /**
     * Fire-and-forget launch of an external player.
     * Used as a fallback when ActivityResultLauncher is not available (e.g. non-Activity context).
     * Builds the same intent as [ExternalPlayerResultContract.createIntent], plus
     * FLAG_ACTIVITY_NEW_TASK (no result is expected on this path).
     */
    fun launch(context: Context, input: ExternalPlayerInput): Boolean {
        return try {
            val intent = ExternalPlayerResultContract().createIntent(context, input)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
