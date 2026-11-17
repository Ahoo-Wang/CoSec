/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.runtime.snapshot

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/**
 * One key's load lane (RFC 0013 B2b, finding 2): what the source's own work produces about the key's loads, as a
 * Treiber stack linked through the events themselves ([KeyEvent.LaneEvent.next]). A base of [KeyDrain], not an object
 * of its own, so a held key keeps one field for it and nothing else. An offer pushes with a CAS; the drain takes the
 * whole stack with one swap and takes it in arrival order ([takeLane]).
 */
internal abstract class KeyLane {
    /** The lane's top: the last event offered, linked to the ones before it down to [END]. */
    @Volatile
    private var top: KeyEvent.LaneEvent<*>? = null

    /** Whether an event waits in the lane. */
    protected val laneWaiting: Boolean get() = top != null

    /** Takes one event of the lane, as the drain takes any event. */
    protected abstract fun takeFromLane(event: KeyEvent.LaneEvent<*>)

    /** Pushes [event]: its link is written, then the CAS publishes both. An event already linked fails an assertion. */
    protected fun push(event: KeyEvent.LaneEvent<*>) {
        assert(event.next == null) { "A lane event is offered once, and $event came back." }
        while (true) {
            val last = top
            event.next = last ?: END
            if (TOP.compareAndSet(this, last, event)) return
        }
    }

    /**
     * Takes the whole lane, in arrival order: the swap empties it, and the chain it returns, newest first, is reversed
     * in place. A taken event keeps [END] as its link, so offering it again is caught. Should a step throw out of
     * [takeFromLane], the events after it go back on the lane rather than be lost.
     */
    protected fun takeLane() {
        var cursor: Any = TOP.getAndSet(this, null) ?: return
        var oldest: Any = END
        while (cursor !== END) {
            val event = cursor as KeyEvent.LaneEvent<*>
            cursor = event.next!!
            event.next = oldest
            oldest = event
        }
        cursor = oldest
        try {
            while (cursor !== END) {
                val event = cursor as KeyEvent.LaneEvent<*>
                cursor = event.next!!
                takeFromLane(event)
            }
        } finally {
            while (cursor !== END) {
                val event = cursor as KeyEvent.LaneEvent<*>
                cursor = event.next!!
                event.next = null
                push(event)
            }
        }
    }

    private companion object {
        /** Sets [top]. */
        val TOP: AtomicReferenceFieldUpdater<KeyLane, KeyEvent.LaneEvent<*>> =
            AtomicReferenceFieldUpdater.newUpdater(KeyLane::class.java, KeyEvent.LaneEvent::class.java, "top")

        /** The end of a lane's chain, and the link a taken event keeps. */
        val END = Any()
    }
}
