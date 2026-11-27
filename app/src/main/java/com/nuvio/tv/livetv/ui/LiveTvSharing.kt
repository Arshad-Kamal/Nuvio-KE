package com.nuvio.tv.livetv.ui

import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * [SharingStarted.WhileSubscribed] ([stopTimeoutMs], value kept through a restart) that also drops the
 * held value back to the initial one once [resets] moves past the count it was derived under and no
 * one collects (Spec 146: a value derived from Live TV's stores keeps them reachable after they unload).
 */
internal fun whileSubscribedResetOn(stopTimeoutMs: Long, resets: StateFlow<Int>): SharingStarted =
    SharingStarted { subscriptionCount ->
        // The count the held value was derived under: the one at the last start.
        var heldFor = resets.value
        combine(SharingStarted.WhileSubscribed(stopTimeoutMs).command(subscriptionCount), resets) { command, count ->
            when {
                command == SharingCommand.START -> {
                    heldFor = count
                    command
                }
                count != heldFor -> SharingCommand.STOP_AND_RESET_REPLAY_CACHE
                else -> command
            }
        }
    }
