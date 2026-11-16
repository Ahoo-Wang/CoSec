# The change flow's transitions

The control plane's transition table (RFC 0007 §5.5, §6.1), as `ChangeStep` in
`core/control/src/main/kotlin/me/ahoo/cosec/control/lifecycle/ChangeStep.kt` declares it. `ChangeTransitionTableTest`
renders that enum and requires it to be this table, so a transition changes only with this file. Each command refuses a
change outside its row's From (409), and no step writes a change into a state outside its row's To.

The first eight rows are the commands. The others are steps those commands take on their way:

- **UNRESERVE:** a stage or skip that did not get its slot.
- **PREEMPT:** a holder whose slot another change takes.
- **UNWRITTEN:** a publish that wrote no head.
- **WRITTEN:** a publish whose head is written, whatever happened to the change meanwhile.
- **EXPIRE:** the expiry every read applies.

<!-- table -->
| Step | From | To |
|---|---|---|
| REBASE | DRAFT, CHECKED, CONFLICTED | DRAFT, CHECKED |
| STAGE | CHECKED | SHADOWING, AWAITING_APPROVAL, READY |
| SKIP_SHADOW | CHECKED | AWAITING_APPROVAL, READY |
| SHADOWED | SHADOWING | SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED |
| APPROVE | AWAITING_APPROVAL | READY |
| REJECT | AWAITING_APPROVAL | REJECTED |
| ABANDON | DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED | ABANDONED |
| PUBLISH | READY | DRAFT, AWAITING_APPROVAL, CONFLICTED, PUBLISHED |
| UNRESERVE | SHADOWING, AWAITING_APPROVAL, READY | CHECKED |
| PREEMPT | SHADOWING, AWAITING_APPROVAL, READY | CONFLICTED |
| UNWRITTEN | READY, CONFLICTED | DRAFT, CONFLICTED |
| WRITTEN | DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED, REJECTED, ABANDONED, EXPIRED | PUBLISHED |
| EXPIRE | DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED | EXPIRED |
<!-- end -->
