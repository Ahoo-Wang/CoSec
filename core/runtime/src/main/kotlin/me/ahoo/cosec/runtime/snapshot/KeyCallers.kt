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
 * One key's caller events (RFC 0013 §5.4): requests, changes, recompile marks, evictions and close, as a Treiber stack
 * of nodes that an idle key does not hold. An offer pushes a node with a CAS; the flag holder swaps the whole stack out,
 * puts it in arrival order, and takes from that [remainder] before anything newer. A remainder left when its budget is
 * spent stays for the next drainer, so caller events are taken in the order they came, each once.
 *
 * A node per offer, as `ConcurrentLinkedQueue` allocates one: a caller event may be a shared object ([KeyEvent.Close],
 * [KeyEvent.Changed]) and cannot carry a link of its own. The queue it replaces cost a held key 72 bytes while empty (the
 * queue and its two nodes); these two fields fit the runner's own object. A base class of [KeyDrain] for that reason,
 * as [KeyLane] is.
 */
internal abstract class KeyCallers : KeyLane() {
    private class Node(val item: Any, var next: Node?)

    /** The caller events offered and not yet swapped out, newest first. */
    @Volatile
    private var arrivals: Node? = null

    /**
     * What the flag holder swapped out, in arrival order, not yet taken: written only while the flag is held, volatile
     * for the checks made after the release ([callerWaiting]) and by `close`.
     */
    @Volatile
    private var remainder: Node? = null

    /** Whether a caller event waits: in the remainder, or offered since. */
    protected val callerWaiting: Boolean get() = remainder != null || arrivals != null

    /** Pushes [item]: the node's link is written, then the CAS publishes both. */
    protected fun enqueue(item: Any) {
        val node = Node(item, null)
        while (true) {
            val last = arrivals
            node.next = last
            if (ARRIVALS.compareAndSet(this, last, node)) return
        }
    }

    /** The oldest caller event not taken, or null: the remainder first, then the arrivals, swapped out whole. */
    protected fun pollCaller(): Any? {
        var head = remainder
        if (head == null) {
            var cursor = ARRIVALS.getAndSet(this, null) ?: return null
            var oldest: Node? = null
            while (true) {
                val next = cursor.next
                cursor.next = oldest
                oldest = cursor
                cursor = next ?: break
            }
            head = oldest!!
        }
        remainder = head.next
        return head.item
    }

    private companion object {
        /** Sets [arrivals]. */
        val ARRIVALS: AtomicReferenceFieldUpdater<KeyCallers, Node> =
            AtomicReferenceFieldUpdater.newUpdater(KeyCallers::class.java, Node::class.java, "arrivals")
    }
}
