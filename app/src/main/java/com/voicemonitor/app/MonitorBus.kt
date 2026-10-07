package com.voicemonitor.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets the foreground service push audio state to the activity without either of
 * them holding a reference to the other. Deliberately coroutine-free so the app
 * keeps a tiny dependency list.
 */
object MonitorBus {

    interface Observer {
        fun onState(state: AudioEngine.State)
    }

    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile
    var state: AudioEngine.State = AudioEngine.State()
        private set

    /** Owned by the running engine; lets the activity drive shadowing controls. */
    @Volatile
    var shadow: ShadowPlayer? = null

    fun add(o: Observer) {
        observers.addIfAbsent(o)
        o.onState(state)
    }

    fun remove(o: Observer) {
        observers.remove(o)
    }

    fun publish(s: AudioEngine.State) {
        state = s
        observers.forEach { runCatching { it.onState(s) } }
    }
}