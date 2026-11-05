# What a revalidation costs Redis

RC review batch D1 (RC1-50, roadmap §7): every instance confirms each installed snapshot document every
`cosec.authorization.snapshot.revalidate-interval` with CoCache's conditional read (`RedisSnapshotDocumentStore.readIfChanged`).
In CoCache 6 that read is a Lua script that hashes the whole stored value with `redis.sha1hex` to compare digests, on
Redis's main thread, so an unchanged document costs Redis time in proportion to its size. CoCache 8.0 keeps the digest
in the value's header and compares only that (its RFC 0005 §4.7, format 2); until then the cost below holds.

## Result

Measured 2026-11-03 with `./gradlew :cocache:measureConditionalRead` (`ConditionalReadCostMeasurement`, opt-in, not part
of `check`) against Redis 7.4.12 in Docker on an 18-CPU arm64 Mac, 500 unchanged conditional reads per size after 50 to
warm up. The documents are platform-shaped (policies of one statement each), padded to the size. "Redis time" is the
server's own time per call (`INFO commandstats`, the scripting commands' `usec` delta divided by their `calls`); the
client times include the round trip through Docker's network, which dominates for small documents.

| Document | Redis time per call | Client p50 | Client p99 |
|---|---|---|---|
| 1 KiB | 11.6 µs | 376 µs | 891 µs |
| 16 KiB | 55.7 µs | 263 µs | 585 µs |
| 128 KiB | 388.2 µs | 548 µs | 737 µs |
| 1.0 MiB | 2,913.3 µs | 3,136 µs | 3,519 µs |
| 4.0 MiB | 11,687.9 µs | 12,074 µs | 12,839 µs |

About 2.8 ms of Redis time per MiB, after a fixed cost of about 10 µs. A Lua script runs alone on Redis's main thread:
while one hashes a 4 MiB document, no other command of that node runs.

## Estimating a deployment

Redis main-thread time per second ≈ instances × Σ (cost of each installed document) ÷ `revalidate-interval`.

- 100 instances, one 1 MiB platform document, 10 s: 100 × 2.9 ms ÷ 10 s ≈ 29 ms per second, about 3 % of the node.
- The same with a 4 MiB platform document: about 12 %.
- 20 instances, a 512 KiB platform document and 200 tenant documents of 8 KiB each (about 35 µs each), 10 s: 20 × (1.5 +
  7) ms ÷ 10 s ≈ 17 ms per second.

## Mitigations

- **Raise `revalidate-interval`** within its bounds (at most 5 minutes, below `lag-warn`, RC1-50): the cost falls in
  proportion, and the time an instance can miss a change whose invalidation message was lost grows the same way.
  Raise `lag-warn` above it.
- **Keep documents small.** The platform document is read by every instance; tenant-specific policies belong in tenant
  documents, which only the instances serving that tenant hold.
- A revision number kept beside each document, compared before the conditional read, was rejected: its write is not
  atomic with the document's, so a failed write could make an instance skip revalidation without bound (the class of
  RC1-50).
