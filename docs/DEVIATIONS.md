# Deviations from the Design Document v1.0

Where the SRS and the Design Document differ, the SRS was followed. Everything else that differs from the design is listed here.

| # | Design | Implementation | Reason |
|---|---|---|---|
| 1 | Presentation layer with passive views and presenters | One screen class per view; screens call application services through `UiContext` and contain no business rules; asynchronous execution is in `UiContext.run` | Fewer classes for ~20 screens; the layering rule (no logic in the UI) is kept |
| 2 | "Four layers and a small cross-cutting infrastructure package" (Table 2 lists five rows) | `infra` is a package that every layer may use, not a fifth layer | Matches the sentence in §2.1 |
| 3 | `ClashDetectionStrategy.findClash(studentId, targetSlots)` | Extra parameter `targetEventId`; implementation `IntervalClashStrategy` is in `EventServices.java` | A waitlisted student must not clash with the event being promoted into; the implementation needs a repository, so it is not in the domain |
| 4 | `RecommendationRule.score(Student, Event)` | `score(RecommendationContext, Event)` | The context carries interests, clubs, attended categories and names, loaded once per request |
| 5 | Recommendations exclude clashing events through the clash strategy | `RecommendationEngine` compares time slots in memory using the same `TimeSlot` rule | One query for the student's registrations instead of one per candidate |
| 6 | `TransactionManager` in the persistence layer | Interface in `Domain.java`, JDBC implementation in `Db.java` | Services must not depend on persistence |
| 7 | `db/seed.sql` creates the initial Admin | `seed.sql` loads reference data; `DbSetup` creates the Admin and optional demo data in Java | The password hash must be computed with the real hasher |
| 8 | `EventState` interface without `withdraw` | Added `withdraw()` (Pending approval → Draft by the proposing officer) | FR-LCY-04 |
| 9 | Notification text stored per language | Notification stores a template key and language-independent parameters; text is built when displayed | The reader's language, not the sender's, decides (FR-LOC) |
| 10 | Staff / Admin permissions in Design Table 6 | Permission matrix of the SRS (Table 3): Staff and Admin also have *Calendar*; Staff cannot decide join requests | SRS wins |
| 11 | Workshop session maintenance as a separate service | `WorkshopService` delegates to `EventService.edit` | One place for permissions, overlap, venue checks and NT-07 |
| 12 | Waitlisted registrations and clash checking | A Waitlisted registration counts as an active registration for clash detection (FR-CLS-01); therefore a waitlisted student cannot register for an overlapping event | Direct reading of FR-CLS-01 |
| 13 | Scheduler "on one client" | Every client runs the scheduler once a minute; all steps are compare-and-set updates and reminders are keyed in `reminder_log` | NFR-REL-05 (no coordinator needed) |
| 14 | Recurrence "monthly" | Monthly on the same day number; months without that day (e.g. 31st) are skipped | Avoids silent date changes |
| 15 | Editing "this and following" occurrences | Following occurrences keep their own dates and take the new time of day, venue, capacity and texts | Keeps previous reschedulings of single occurrences |
| 16 | Packages `domain`, `service`, `persistence`, `ui` with one class per file | The same classes grouped into 13 source files in one package (`Domain.java`, `Db.java`, `UserServices.java`, `EventServices.java`, `ClubServices.java`, `UiKit.java`, ...) | Requested to keep the project small; class names, responsibilities and layering are unchanged (see `docs/CLASS_INDEX.md`) |
