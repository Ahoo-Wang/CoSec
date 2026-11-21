# CTL-66: a listener receives one change's events in the order they were written. Proposal, no code

Read against main 56a03e1f9 (`core/control/.../audit/ChangeListeners.kt`, `core/runtime/.../event/DecisionListeners.kt`).
Nothing is built.

## What happens today
`ChangeListeners.publish(event, gated)`:
- **A gated step** (`PUBLISHING`, `BREAK_GLASS_REQUESTED`, `BREAK_GLASS_APPROVED`, a break-glass abandon) delivers to
  every REQUIRED listener synchronously, all of them together under `requiredTimeout`. The step fails with 503 if one
  of them does not record it. Every BEST_EFFORT listener then gets the event through `later()`.
- **Any other step** reaches every listener, REQUIRED ones included, through `later()`.
- **`later()`** is `scope.launch` per event per listener on `Dispatchers.Default`. Two events are two coroutines, so a
  listener can receive them in either order.

Where this breaks order, seen on CI in #1795 and by reading the code:
- **Two steps of one change from consecutive calls** (`CHECKED` from propose, then `SHADOW_SKIPPED`) are launched
  apart. Any listener may get the second first.
- **One step that writes two events** (`PUBLISHING` gated, then `PUBLISHED`):
  - A BEST_EFFORT listener gets both from `later()`, in either order. The Kafka change listener configured best
    effort, which is the default for the change log, regularly writes `PUBLISHED` before `PUBLISHING`.
  - A REQUIRED listener gets `PUBLISHING` synchronously and then `PUBLISHED` launched, so it sees them in order. But
    it gets a non-gated event of the *same change*, written earlier and still unlaunched, in any order relative to
    the gated one.

Kafka keeps per-key order only if the producer sends in order. So today the change stream's per-key order is lost
before the producer is reached.

## The property
Within one control-plane instance, a listener receives the events of one change in the order the instance wrote them.

Across instances there is no order to keep:
- propose on A and publish on B are two processes writing to Kafka;
- a consumer that needs a total order uses the event's `time` and the change's record.

The docs and the rule say "within an instance".

## Shape A (recommended): one FIFO queue and one consumer per listener, as `DecisionListeners` has
- **The queue.** Each listener gets a queue and one consumer coroutine. `publish` enqueues the event on every
  listener's queue, in the order of the calls. The consumer delivers one event at a time, so one listener sees the
  instance's events in the order they were written. That is every change's order, and more than CTL-66 needs.
- **REQUIRED listeners and gated steps.** The event goes through the same queue, and the step awaits its delivery: a
  `CompletableDeferred` completed by the consumer, within `requiredTimeout`.
  - The wait therefore includes the events still queued before it, so a REQUIRED listener never records a gated
    event ahead of an earlier one.
  - A step that cannot be recorded in time still fails with 503, as today. The timeout now also covers a backlog,
    which is right: the earlier events must be recorded first.
  - The REQUIRED listeners still run in parallel with each other, one consumer each, and the step awaits all of them.
- **REQUIRED listeners and non-gated steps:** enqueued and not awaited, as today, where they are launched and not
  awaited.
- **BEST_EFFORT listeners:** enqueued, never awaited.
- **Back-pressure** (a bounded queue per listener, capacity a property, say 10,000):
  - **BEST_EFFORT, full queue:** the event is dropped, counted, and logged at most once per interval, exactly as
    `DecisionListeners` does. A change event dropped from a best-effort sink is what best effort means today when the
    sink fails. Control-plane events come at human or service rate, so a full queue means the sink is down.
  - **REQUIRED, full queue:** enqueueing suspends the step until there is room or `requiredTimeout` passes, then 503.
    Never a drop, since REQUIRED means the step does not happen unrecorded. A non-gated event for a REQUIRED listener
    also waits for room; it is never dropped.
- **Shutdown:** `close()` stops taking events. `drain(timeout)` lets the consumers deliver what is queued, then stops
  and counts what is left (`DecisionListeners.drain`). `cancel()` stays the hard stop. `awaitDelivered()` becomes
  "every queue empty and every consumer idle", which tests already use.
- **Cost:** one coroutine per listener (two or three listeners) instead of one per event, and a bounded queue each. On
  the request path, publishing becomes an `offer` (BEST_EFFORT) or an `offer` plus an await (REQUIRED, gated): no
  slower than today.

## Shape B: sequencing per change
A lane per change id within each listener: a map from change id to a serial queue, created on the first event and
evicted when idle. Events of one change are ordered, and different changes are delivered in parallel.
- **What it gains:** parallel delivery across changes, which does not matter at control-plane event rates.
- **What it costs:** a map that must be evicted, a lane's lifetime to reason about at shutdown, and REQUIRED gated
  waits that still serialize per change.
- **REQUIRED, back-pressure and shutdown:** the same rules as A, per lane. The bound becomes per listener over all
  lanes, so it is harder to state.

I would not take B unless a sink is too slow to serve changes one at a time. None is: Kafka sends asynchronously, and
the log listener is synchronous and fast.

## What a test of it looks like
- **Deterministic first, as #1779.** A listener whose `onChange` suspends on a gate for the first event it gets:
  - publish `CHECKED`, then `SHADOW_SKIPPED`, of one change;
  - release the gate;
  - assert the listener received `CHECKED, SHADOW_SKIPPED`.

  On main the second is delivered while the first is held, so the test fails there.
- **The same for a REQUIRED listener with a gated step:** a non-gated event queued and held, then `PUBLISHING`. The
  step completes only after both are recorded, in order.
- **Random:** many changes, each publishing a run of events from concurrent callers, with listeners whose calls take
  random time. Per change, the received order equals the published order. Asserted once everything settles.
- **Back-pressure:**
  - a BEST_EFFORT queue of capacity 2 under a held listener drops the third event, counted;
  - a REQUIRED one makes the third step wait, then answer 503 at the timeout;
  - shutdown with a backlog delivers it within the drain timeout and counts the rest.
- **Kafka:** `KafkaChangeListener` over a recording producer receives `PUBLISHING` before `PUBLISHED` for every
  `apply` in a burst.
- **Mutant:** deliver each queued event in a coroutine of its own (today's `later()`). The deterministic test kills
  it.

## What changes outside the class
- **Docs:** `observability.md` gets one sentence on order within an instance, one on the queue capacity property
  (`configuration.md`, en and zh), and one on what a dropped best-effort change event costs.
- **Rule:** `CTL-66` in `CTL.json`, with a positive and a negative scenario. The runner's `audited` step can then
  assert order instead of sorting (`#1795` sorted it because order was not promised).
- **ABI:** `ChangeListeners` is public, so a new constructor parameter (`queueCapacity`, with a default) is a dump
  line. Its `awaitDelivered` is `@InternalCoSecApi`.

## Decisions for you
1. A or B. I recommend A.
2. That a full BEST_EFFORT change-event queue drops (counted) rather than blocks, and a REQUIRED one blocks then 503s.
3. The scope, "within an instance", stated in the rule and the docs.
