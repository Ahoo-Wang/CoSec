# PR B (#1729) evidence: back-references as set members

The harness is `SaasScaleMeasurement` from the local branch `measure/saas-scale`, adapted to the new port. It is not committed. It ran in memory, niced, with a 4 GB heap, and counted store operations at the sizes CoCache sends.

The world: the platform, 12 apps, and N tenant documents. Each tenant holds 5 custom roles over the same 20 permissions of one app, and 20 role bindings, one of them to a platform policy.

## A tenant's publish (CTL-44)

| Tenants | One back-reference set | New tenant's publish | Edit: one binding to a new permission | Edit: drop 2 references |
|---|---|---|---|---|
| 10,000 | 10,000 members, 146 KB | 21 member adds (~15 B each); index 3 GET (3 KB) + 2 PUT (3 KB); record 2 KB; head 2 KB | 1 member add; head read 2 KB; index 3 GET + 2 PUT (~9 KB) | 2 member removals; head read 2 KB; index 4 GET + 3 PUT (~14 KB); no set read |
| 50,000 | 50,000 members, 732 KB | identical | identical | identical |

- Every set write also sends one PUBLISH, on `<s>:control:inv:refs`. Only processes that run the control plane subscribe to it.
- So a tenant publish that starts referring to 21 policies sends 21 member writes and 21 PUBLISH commands, however many tenants there are.

## 50 tenants publishing at once

| Tenants | Latency model | Published | Failed | Conditional-write conflicts | Wall time |
|---|---|---|---|---|---|
| 10,000 | in memory | 50 | 0 | 0 | 13 ms |
| 10,000 | 1 ms a command + 8 ms a MB | 50 | 0 | 0 | 41 ms |
| 50,000 | in memory | 50 | 0 | 0 | 4 ms |
| 50,000 | 1 ms a command + 8 ms a MB | 50 | 0 | 0 | 42 ms |

The conflicts column counts lost index writes and lost head writes. There are none, because each tenant writes only its own index and its own members.

## Removal checks

| Tenants | What | Outcome | Time | Store traffic |
|---|---|---|---|---|
| 10,000 | app a0 removes p1, which every tenant's roles list | Referenced (10,000) | 6 ms | 1 set read, 146 KB |
| 10,000 | platform removes base-access, which every tenant binds | Referenced (10,000) | 5 ms | 1 set read, 146 KB |
| 10,000 | change on app a0 removing p1 (propose) | DRAFT, REFERENCES | 13 ms | 2 set reads, 146 KB |
| 10,000 | change on the platform removing base-access (propose) | DRAFT, REFERENCES | 10 ms | 1 set read, 146 KB |
| 50,000 | app a0 removes p1 | Referenced (50,000) | 12 ms | 1 set read, 732 KB |
| 50,000 | platform removes base-access | Referenced (50,000) | 12 ms | 1 set read, 732 KB |
| 50,000 | change on app a0 removing p1 (propose) | DRAFT, REFERENCES | 18 ms | 2 set reads, 732 KB |
| 50,000 | change on the platform removing base-access (propose) | DRAFT, REFERENCES | 12 ms | 1 set read, 732 KB |

No tenant head is read in any of these checks.

## Unchanged by PR B

- A reclamation pass: 10,000 index and head reads, no write, 43 ms at 10,000 tenants; 155 ms at 50,000.
- A tenant change's grant analysis: it reads the document set once, for the app list's reconciliation.
