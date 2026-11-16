# Testing

## Automated (JUnit 5): 38 tests (all in `Tests.java`)
Run `./test.sh` (or `mvn test`). The integration tests (`*IT`) use the database `uems_test` and are skipped if it is unreachable;
each test class recreates the schema. Password hashing uses 1000 iterations in the tests (600 000 in production).

| Class | Tests | What it proves |
|---|---|---|
| `DomainRulesTest` | 13 | slot overlap, event / registration state machines, publish validation, default deadlines, workshop composite, recurrence, PBKDF2, Arabic normalization, iCalendar escaping and folding, localized text |
| `RegistrationIT` | 8 | 30 threads racing for 5 seats give exactly 5 confirmed and 25 waitlisted with unique positions; concurrent cancellations promote FIFO; clash skip on promotion (NT-04); overlap refused / back-to-back allowed; every workshop session checked; deadlines; permissions |
| `WorkflowIT` | 10 | lifecycle driven by a fake clock and idempotent reminders; event cancellation; venue double-booking and capacity; proposal workflow and officer rights; BR-01 / last officer; series atomicity and conflicts; forced password change; lockout and session timeout; check-in → results → completion → feedback; search, recommendations, discussion, calendar export |
| `UserIdIT` | 7 | student university number: nine digits starting with 20, generation of blank IDs, duplicates, CSV import, legacy IDs |

## GUI smoke test (manual run, not part of `test.sh`)
`src/test/java/uems/ui/UiSmoke.java` starts the real application window under a virtual display (`xvfb-run`), logs in as a student, a staff member,
an officer and the administrator, opens about 30 screens (also in Arabic) and the organizer dialogs (registrants, check-in, announcement, history, results, club details), and writes PNG screenshots to `/tmp/shots`.
```
./start.sh setup --reset --demo
xvfb-run -a -s "-screen 0 1280x800x24" java -cp "out/classes:out/test-classes:<jdbc.jar>" uems.UiSmoke
```
It found and led to the fixes of: the forced password change flow, form layout (width, text-area height), truncated table columns, and a screen constructor that
failed for a non-organizer role.

## Not covered by automation
* Buttons inside the dialogs and screens are not clicked by automation (the smoke run opens the dialogs and loads their data, but does not press
  e.g. "Mark present", "Publish results", the series-preview OK button or the file choosers). The service calls behind them are covered by the
  integration tests; the Swing wiring of those buttons was checked by construction and code review only.
* Maven build, Windows, macOS.
* Load / response-time measurements.
