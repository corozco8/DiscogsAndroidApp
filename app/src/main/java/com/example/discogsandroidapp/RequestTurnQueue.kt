package com.example.discogsandroidapp

/** Called under the pacing lock. Foreground work wins, with a bounded burst for fairness. */
internal class RequestTurnQueue {
    class Ticket(val background: Boolean)
    private val pending = mutableListOf<Ticket>()
    private var foregroundBurst = 0
    fun add(background: Boolean) = Ticket(background).also { pending.add(it) }
    fun remove(ticket: Ticket) { pending.remove(ticket) }
    fun isNext(ticket: Ticket): Boolean {
        val background = pending.firstOrNull { it.background }
        val foreground = pending.firstOrNull { !it.background }
        return ticket === if (foregroundBurst >= 5 && background != null) background
            else foreground ?: background
    }
    fun take(ticket: Ticket) {
        check(isNext(ticket))
        pending.remove(ticket)
        foregroundBurst = if (ticket.background) 0 else foregroundBurst + 1
    }
}

internal enum class DiscogsRequestPriority { FOREGROUND, BACKGROUND }
