# The change flow's transitions

The control plane's transition table (RFC 0007 §5.5, §6.1), as `ChangeStep` in
`core/control/src/main/kotlin/me/ahoo/cosec/control/lifecycle/ChangeStep.kt` declares it. `ChangeTransitionTableTest`
renders that enum and requires it to be this table, so a transition changes only with this file. A row's To holds
exactly the states that transitions under that step write.

Each command refuses a change outside its row's From (409). The other steps are not commands and are never refused with
a 409. No step writes a change into a state outside its row's To, or from a state outside its row's From: such a write
is a fault, nothing is stored, and the control API answers with a server error, never a 409.

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
| PUBLISH | READY | DRAFT, AWAITING_APPROVAL, CONFLICTED |
| UNRESERVE | SHADOWING, AWAITING_APPROVAL, READY | CHECKED |
| PREEMPT | SHADOWING, AWAITING_APPROVAL, READY | CONFLICTED |
| UNWRITTEN | READY, CONFLICTED | DRAFT, CONFLICTED |
| WRITTEN | DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED, REJECTED, ABANDONED, EXPIRED | PUBLISHED |
| EXPIRE | DRAFT, CHECKED, SHADOWING, AWAITING_APPROVAL, READY, CONFLICTED | EXPIRED |
<!-- end -->

WRITTEN's From holds REJECTED, ABANDONED and EXPIRED. A publish writes the head while its change is READY. If another
command (a reject, an abandon, an expiry) moves the change before the publish records that, the head is written all
the same, so the change becomes PUBLISHED. Nothing but the head write of this change's own publish reaches WRITTEN.
