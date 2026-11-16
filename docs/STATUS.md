# Implementation status

Status of the implementation against the SRS v1.0 (High / Medium / Low priority). "Verified" means covered by an automated test or by the
GUI smoke run described in `TESTING.md`.

## Implemented
| Area | Status | Verified by |
|---|---|---|
| Authentication: login, lockout (5 failures / 15 min), idle timeout, logout, forced change of temporary password, password policy | complete | `WorkflowIT` (lockout, timeout, forced change), GUI smoke |
| Authorization: permission matrix (Table 3), club-scoped officer rights, Admin has no event rights | complete | `RegistrationIT`, `WorkflowIT` |
| User administration: create, edit, disable, reset password, anonymize, CSV import, audit log, settings | complete | GUI smoke (screens); service paths partly in `WorkflowIT` |
| Venues: CRUD, deactivation guard, timetable, availability check, atomic reservation, double-booking prevention | complete | `WorkflowIT` |
| Events: general, workshop (sessions), competition; draft, edit, publish, copy, delete, cancel with reason, poster | complete | `WorkflowIT`, `DomainRulesTest` |
| Lifecycle: 8 states, scheduler (open / close / start / complete), manual close, history | complete | `WorkflowIT`, `DomainRulesTest` |
| Proposals: submit, withdraw, approve (venue re-check), reject with reason | complete | `WorkflowIT` |
| Recurring series: daily / weekly / monthly, preview with conflicts, skip dates, atomic create, edit or cancel this / following | complete | `DomainRulesTest`, `WorkflowIT` |
| Registration, waitlist (FIFO), promotion, clash skip, cancellation deadline, cancel on behalf, no overbooking under concurrency | complete | `RegistrationIT` (30-thread race) |
| Schedule-clash detection incl. workshop sessions, back-to-back allowed | complete | `RegistrationIT`, `DomainRulesTest` |
| Check-in (workshop per session), undo (audited), attendance totals | complete | `WorkflowIT` |
| Competition results: draft, publish, correct (Staff, audited) | complete | `WorkflowIT` |
| Feedback (30-day window, 7-day edit, anonymous for officers), discussion (threads, edit, delete, moderation, read-only after 30 days) | complete | `WorkflowIT` |
| Clubs: browse, join requests, approve / reject, leave, remove member, officers (BR-01, last officer), announcements | complete | `WorkflowIT` |
| Search: bilingual keyword (Arabic normalization), filters, sorting, paging, guest access | complete | `WorkflowIT` |
| Recommendations: configurable rule engine, reasons, popular fallback | complete | `WorkflowIT` |
| Calendar: month view and list, `.ics` export (RFC 5545, folding, stable UIDs) | complete | `DomainRulesTest`, `WorkflowIT`, GUI smoke |
| Notifications: all 16 triggers (NT-01 … NT-16) as domain events, notification centre, unread badge polled every 30 s | complete | `RegistrationIT`, `WorkflowIT`, GUI smoke |
| Bilingual UI (English / Arabic), immediate switch, right-to-left layout, Arabic dates | complete | GUI smoke (both languages) |
| Persistence: PostgreSQL schema with constraints, pooling, transactions with deadlock retry, optimistic locking | complete | all integration tests |

## Known gaps and limitations
* **FR-CLS-04** (Low) – the clash report when an organizer reschedules an event is not implemented. The waitlist promotion does handle a clash that
  appears after rescheduling (tested).
* **NFR-PRV-04** (retention) – partial. After the retention period the scheduler deletes feedback comments and free-text cancellation reasons.
  Complete anonymization is available per user on request (Administrator → Users → Anonymize) but is not automatic.
* An account-type change takes effect at the user's next login (the session holds a snapshot of the type). Club-officer rights are checked live.
* Dates and times are typed as text (`yyyy-MM-dd HH:mm`); there is no date picker.
* Notifications are in-app only (no e-mail).
* Posters are PNG/JPEG up to 2 MB and shown on the event page only (no thumbnails in lists).
* Performance requirements (NFR-PRF) were not measured with realistic data volumes; only functional behaviour was tested.
* Not verified in the development environment: the Maven build (`pom.xml`; the sandbox could not reach Maven Central), Windows and macOS runs,
  and button clicks inside Swing dialogs (the dialogs open and load their data in the smoke run, but are not clicked through; see `TESTING.md`).
