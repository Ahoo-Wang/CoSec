# The client contract

What a client of a CoSec application relies on, as JSON files that both sides test against (`PIP-25`). The words
around it, what a client does with each answer, are in
[Building a client](https://cosec.ahoo.me/integration/clients).

| File | What it holds | Written and checked by |
|---|---|---|
| `problems/<slug>.json` | For each problem type: the status, the response headers, the `application/problem+json` body, and `optionalMembers` (members a body may carry besides, by type: `traceId`) | `runtime:ClientContractTest` |
| `headers.json` | The `CoSec-*` request headers, the request id's grammar, the response headers of each outcome | `runtime:ClientContractTest` |
| `token/composite-token.json` | The token pair's body (the values are placeholders) | `jwt:ClientContractTest` |
| `token/claims.json` | The claims of CoSec's access and refresh tokens, each with its JSON type and `client`: `read`, or `do-not-depend` | `jwt:ClientContractTest` |
| `routes/refresh.json`, `routes/logout.json` | What the served refresh and logout routes answer: `cacheControl`, and each case's status, media type and body (`pair`, a problem slug, or none) | `jwt:ClientContractTest` |

**The code writes these files; nobody edits them by hand.** A test in each module compares what the code answers with
the file and fails on a difference. After an intended change, rewrite the files and commit them with the change:

```bash
./gradlew :runtime:updateClientContract :jwt:updateClientContract
```

**What is compared:** parsed JSON. Every member name, type and value is as the file says, with no member missing and
none extra; member order and whitespace are not part of the contract, and a client must not depend on them.

**Compatibility (from 8.0's GA):**
- A removed or changed member, value, status or header is a breaking change for clients: the PR title carries `!`.
- A new problem type, a new optional member (listed in `optionalMembers`), a new header or a new claim is not. A client
  defaults on a type it does not know, and ignores what it does not read.
