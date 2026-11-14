# UEMS – University Event Management System

Java 17 / Swing desktop application on PostgreSQL. Implements the UEMS SRS v1.0 and Design Document v1.0:
four layers (presentation, application services, domain, persistence) plus a small cross-cutting `infra` package.

## 1. Requirements
* JDK 17 or newer (developed and tested with JDK 21 using `--release 17`)
* PostgreSQL 15 or newer
* The PostgreSQL JDBC driver (fetched automatically by Maven, or put `postgresql-*.jar` into `lib/`)
* Optional: Maven 3.9+. A plain `javac` build is also provided.

## 2. Database
```sql
CREATE USER uems WITH PASSWORD 'uems';
CREATE DATABASE uems OWNER uems;
CREATE DATABASE uems_test OWNER uems;   -- only for running the integration tests
```
Copy `uems.properties.example` to `uems.properties` (it is git-ignored) and adjust `db.url`, `db.user`, `db.password`.

## 3. Build and create the schema
* **Windows (no Maven needed):** double-click or run `build.bat` (needs a JDK 17+ on the PATH; it also downloads the JDBC driver into `lib\`).
* Linux / macOS: `./build.sh` (put the PostgreSQL JDBC jar into `lib/`)
* Maven (any OS): `mvn package`

Create the schema, reference data (categories, tags, settings) and the initial administrator:
```
./start.sh setup --reset --demo      # Windows:  run.bat setup --reset --demo
```
`--demo` additionally loads 3 Staff, 20 students, 5 venues, 3 clubs and ~15 events in different states.
`--reset` drops and recreates everything in the configured database.

## 4. Run
```
./start.sh          # Windows: run.bat   (a window that closes at once means a setup step was skipped; run it from a cmd prompt to read the message)
```

### Accounts
| ID | Password | Role |
|---|---|---|
| `admin` | `ChangeMe123` (must be changed at first login) | Administrator |
| `T1001`, `T1002`, `T1003` | `Demo1234` | Staff (only with `--demo`) |
| `202610001` … `202610020` | `Demo1234` | Students (only with `--demo`) |

`202610001`, `202610002`, `202610003` are the officers of the Robotics Club, Cultural Society and Sports Club. `202610016` has a pending join request.
The *Football Friendly* event has 3 seats and a waitlist; *Cultural Night* is a pending proposal for Staff to approve.

### Using the application
* **Language**: the button at the top right switches English ⇄ Arabic (right-to-left layout) immediately; it is stored in the profile.
* **Guests** can browse published events from the login screen ("Browse as guest").
* **Students**: home (recommendations), browse/search, register or join the waitlist, cancel, calendar view and `.ics` export, clubs, notifications, feedback and discussion after events, profile and interests.
* **Club officers** (a position inside a club): club page with members and join requests, announcements, proposing events for approval, check-in, results and registrants for the club's own events.
* **Staff**: create/edit/publish/cancel events (including recurring series, workshops, competitions), approve or reject proposals, manage clubs and officers, check-in, results, moderation, categories and tags.
* **Administrators**: users (create, edit, disable, reset password, anonymize, CSV import), venues and timetables, audit log, system settings.
* Dates are entered as `yyyy-MM-dd HH:mm` in Gulf Standard Time (UTC+4); everything is stored in UTC.
* Arabic text needs a font with Arabic glyphs (present on Windows, macOS and most Linux desktops; on minimal Linux install e.g. `fonts-dejavu`).

CSV import format (one user per line, `#` starts a comment): `universityId,nameEn,nameAr,email,type,faculty` with type `STUDENT`, `STAFF` or `ADMIN`.

## 5. Tests
```
./test.sh            # plain javac + JUnit 5 console launcher       (or: mvn test)
```
* `DomainRulesTest` – pure unit tests (time slots, state machines, recurrence, hashing, Arabic normalization, iCalendar).
* `RegistrationIT`, `WorkflowIT` – integration tests against the `uems_test` database (skipped automatically if it is not reachable):
  concurrent registration race, waitlist, clash detection, lifecycle/scheduler, proposals, venue booking, series, lockout and session timeout, check-in, results, feedback, search, recommendations, discussion, calendar export.
* `uems.UiSmoke` – drives the real Swing UI under a virtual display and saves screenshots (see `docs/TESTING.md`).

## 6. Project layout
The code is deliberately kept in **13 Java files** (all in the package `uems`) plus one test file. Each file holds many separate classes
(see `docs/CLASS_INDEX.md` for the list of classes and line numbers).
```
src/main/java/uems/
  AppContext.java      composition root (wires everything)
  UemsApp.java         application entry point (main)
  DbSetup.java         database setup tool + demo data
  Domain.java          domain layer: entities, state machines, rules, domain events, exceptions, repository interfaces
  Infra.java           configuration, password hashing, Arabic normalization, tokens
  Db.java              persistence layer: JDBC pool, transaction manager, repositories
  UserServices.java    module 1 services: auth, sessions, users, venues, notifications, audit, settings
  EventServices.java   module 2 services: events, lifecycle, registration, waitlist, check-in, scheduler, calendar export
  ClubServices.java    module 3 services: clubs, results, feedback, discussion, search, recommendations
  UiKit.java           UI base: messages, formats, UiContext, Screen, DataTable, builders, MainFrame
  UserScreens.java     module 1 screens (login, profile, notifications, users, venues, audit)
  EventScreens.java    module 2 screens (event details, editor, management, my registrations, organizer dialogs)
  ClubScreens.java     module 3 screens (browse, home, clubs, club management, discussion, feedback)
src/test/java/uems/Tests.java   all JUnit tests, the test fixture and the GUI smoke run
src/main/resources/            db/schema.sql, db/seed.sql, i18n bundles, recommendation.properties
tools/                         messages.src + gen_bundles.py (English/Arabic texts), class_index.py
docs/                          STATUS.md, DEVIATIONS.md, TESTING.md, CLASS_INDEX.md
```
Texts live in `tools/messages.src` (`key ## English ## Arabic`); run `python3 tools/gen_bundles.py` after editing to regenerate both property files.
Recommendation rules and weights are in `src/main/resources/recommendation.properties` (a new rule is a class plus one line there).

## 7. Documentation conventions
Every class has a Javadoc comment naming the SRS/Design element it implements (use cases `UC-n`, requirements `FR-…`, `NFR-…`, business rules `BR-n`).
Trivial getters/setters are not commented individually.
