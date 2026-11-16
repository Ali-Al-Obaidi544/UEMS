package uems;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.time.*;
import java.time.Duration;
import java.util.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// All automated tests (JUnit 5) and the manual GUI smoke run in one file.

/** Integration-test fixture: fresh schema in the test database, a controllable clock and builders. */
final class TestEnv implements AutoCloseable {
    /** A clock the test can move forward. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;
        public MutableClock(Instant start) { this.now = start; }
        public void set(Instant t) { now = t; }
        public void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static final Instant START = Instant.parse("2026-11-10T06:00:00Z");
    public final MutableClock clock = new MutableClock(START);
    public final AppContext ctx;
    public final UserSession admin, staff;
    public final long venueBig, venueSmall, category;
    private int counter;

    public static Config config() {
        Properties p = new Properties();
        p.setProperty("db.url", System.getProperty("uems.test.db.url", "jdbc:postgresql://localhost:5432/uems_test"));
        p.setProperty("db.user", System.getProperty("uems.test.db.user", "uems"));
        p.setProperty("db.password", System.getProperty("uems.test.db.password", "uems"));
        p.setProperty("db.pool.size", "20");
        p.setProperty("security.pbkdf2.iterations", "1000");
        return new Config(p);
    }

    /** True when the test database is reachable; tests are skipped otherwise. */
    public static boolean available() {
        Config c = config();
        try (var conn = java.sql.DriverManager.getConnection(c.require("db.url"), c.require("db.user"), c.get("db.password", ""))) { return true; }
        catch (Exception e) { return false; }
    }

    public TestEnv() throws Exception {
        DbSetup.setup(config(), true, false);
        ctx = new AppContext(config(), clock);
        admin = ctx.auth.login("admin", DbSetup.INITIAL_ADMIN_PASSWORD.toCharArray());
        admin.setMustChangePassword(false);
        ctx.sessions.setTimeout(Duration.ofDays(3650));   // tests move the clock far ahead
        staff = session(user(AccountType.STAFF, "T1"));
        venueBig = venue("Big Hall", 100);
        venueSmall = venue("Small Room", 10);
        category = ctx.persistence.tx.inTransaction(() -> ctx.persistence.catalog.findCategories().get(0).id());
    }

    public long venue(String name, int capacity) {
        Venue v = new Venue();
        v.setName(LocalizedText.of(name)); v.setCapacity(capacity);
        return ctx.venues.save(admin, v).getId();
    }


    public User user(AccountType type, String id) {
        User u = User.create(type);
        u.setUniversityId(id); u.setFullName(LocalizedText.of("User " + id)); u.setEmail(id + "@x.example");
        u.setPasswordHash(ctx.hasher.hash("Passw0rd!".toCharArray()));
        return ctx.persistence.tx.inTransaction(() -> ctx.persistence.users.save(u));
    }

    public UserSession session(User u) { return ctx.sessions.create(u); }

    public UserSession student() { return session(user(AccountType.STUDENT, "S" + (++counter))); }

    /** Time at the given hour in Gulf Standard Time, {@code day} days after the fixture start day. */
    public Instant at(int day, int hour) {
        return LocalDate.ofInstant(START, Gst.ZONE).plusDays(day).atTime(hour, 0).atZone(Gst.ZONE).toInstant();
    }

    public EventData data(String title, int day, int hour, int hours, long venue, int capacity) {
        EventData d = new EventData();
        d.title = LocalizedText.of(title); d.categoryId = category; d.startAt = at(day, hour); d.endAt = d.startAt.plus(Duration.ofHours(hours));
        d.venueId = venue; d.capacity = capacity;
        return d;
    }

    /** Creates, publishes and opens a general event. */
    public long openEvent(String title, int day, int hour, int hours, long venue, int capacity) {
        Event e = ctx.events.create(staff, data(title, day, hour, hours, venue, capacity));
        ctx.events.publish(staff, e.getId());
        ctx.scheduler.tick();
        return e.getId();
    }

    @Override public void close() { ctx.close(); }
}

/** Pure unit tests of domain rules; no database needed. */
class DomainRulesTest {
    private static final Instant T0 = Instant.parse("2026-11-10T06:00:00Z");
    private static TimeSlot slot(int fromH, int toH) { return new TimeSlot(T0.plus(Duration.ofHours(fromH)), T0.plus(Duration.ofHours(toH))); }

    @Test void backToBackSlotsDoNotClash() {                       // BR-07, FR-CLS-03
        assertFalse(slot(0, 2).overlaps(slot(2, 4)));
        assertTrue(slot(0, 3).overlaps(slot(2, 4)));
        assertTrue(TimeSlot.firstOverlap(List.of(slot(0, 1), slot(5, 7)), List.of(slot(6, 8))).isPresent());
        assertTrue(TimeSlot.firstOverlap(List.of(slot(0, 1)), List.of(slot(1, 2))).isEmpty());
    }

    @Test void slotRejectsEndBeforeStart() { assertThrows(ValidationException.class, () -> slot(3, 3)); }

    @Test void eventStateMachineAllowsOnlyLegalTransitions() {     // FR-LCY-01
        Event e = new GeneralEvent();
        assertEquals(EventStatus.DRAFT, e.status());
        assertThrows(IllegalTransitionException.class, e::open);
        assertThrows(IllegalTransitionException.class, e::approve);
        e.submit(); assertEquals(EventStatus.PENDING_APPROVAL, e.status());
        e.reject(); assertEquals(EventStatus.DRAFT, e.status());
        e.publish(); e.open(); e.close(); e.start(); e.complete();
        assertEquals(EventStatus.COMPLETED, e.status());
        assertThrows(IllegalTransitionException.class, e::cancel);
        Event c = new GeneralEvent(); c.publish(); c.cancel();
        assertThrows(IllegalTransitionException.class, c::open);
    }

    @Test void publishValidationChecksCapacityAndTimes() {         // FR-EVT-03
        Instant now = T0;
        Event e = new GeneralEvent();
        e.setTitle(LocalizedText.of("X")); e.setVenueId(1L); e.setCapacity(50);
        e.setStartAt(now.plusSeconds(3600)); e.setEndAt(now.plusSeconds(7200));
        e.validateForPublish(now, 100);
        e.setCapacity(101);
        assertThrows(ValidationException.class, () -> e.validateForPublish(now, 100));
        e.setCapacity(0);
        assertThrows(ValidationException.class, () -> e.validateForPublish(now, 100));
        e.setCapacity(10); e.setStartAt(now.minusSeconds(5)); e.setEndAt(now.plusSeconds(10));
        assertThrows(ValidationException.class, () -> e.validateForPublish(now, 100));
    }

    @Test void defaultDeadlines() {                                // BR-05
        Event e = new GeneralEvent(); e.setStartAt(T0.plus(Duration.ofDays(2)));
        assertEquals(T0.plus(Duration.ofDays(1)), e.effectiveCancelDeadline());
        assertEquals(e.getStartAt(), e.effectiveRegDeadline());
        e.setCancelDeadlineAt(e.getStartAt().plusSeconds(1));
        assertThrows(ValidationException.class, e::validateBasics);
    }

    @Test void registrationStateTransitions() {
        Registration r = Registration.waitlisted(1, 1, T0);
        assertThrows(IllegalTransitionException.class, () -> r.markAttended(T0, 1));
        r.promote(); assertEquals(RegistrationStatus.CONFIRMED, r.status());
        r.markAttended(T0, 9); assertEquals(RegistrationStatus.ATTENDED, r.status());
        assertThrows(IllegalTransitionException.class, () -> r.cancel(T0, 1L, null));
        r.undoAttendance(); assertEquals(RegistrationStatus.CONFIRMED, r.status());
        r.cancel(T0, 1L, "x"); assertThrows(IllegalTransitionException.class, r::promote);
    }

    @Test void workshopDerivesBoundsAndRejectsOverlappingSessions() {   // FR-WSH-03
        Workshop w = new Workshop();
        w.setTitle(LocalizedText.of("W"));
        w.setSessions(List.of(new WorkshopSession(LocalizedText.of("b"), slot(30, 32), null), new WorkshopSession(LocalizedText.of("a"), slot(0, 2), null)));
        assertEquals(slot(0, 2).start(), w.getStartAt());
        assertEquals(slot(30, 32).end(), w.getEndAt());
        assertEquals(2, w.getTimeSlots().size());
        w.setSessions(List.of(new WorkshopSession(LocalizedText.of("a"), slot(0, 3), null), new WorkshopSession(LocalizedText.of("b"), slot(2, 4), null)));
        assertThrows(ValidationException.class, w::validateBasics);
    }

    @Test void weeklyRecurrenceOnChosenWeekdays() {                // FR-REC-01
        ZoneId z = Gst.ZONE;
        Instant first = LocalDate.of(2026, 11, 9).atTime(10, 0).atZone(z).toInstant();      // a Monday
        RecurrenceRule r = new RecurrenceRule(RecurrenceRule.Frequency.WEEKLY, 1, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), null, 5);
        List<TimeSlot> s = r.expand(new TimeSlot(first, first.plusSeconds(3600)), z);
        assertEquals(5, s.size());
        assertEquals(DayOfWeek.MONDAY, s.get(0).start().atZone(z).getDayOfWeek());
        assertEquals(DayOfWeek.WEDNESDAY, s.get(1).start().atZone(z).getDayOfWeek());
        assertEquals(DayOfWeek.MONDAY, s.get(2).start().atZone(z).getDayOfWeek());
        assertEquals(LocalTime.of(10, 0), s.get(4).start().atZone(z).toLocalTime());
    }

    @Test void dailyMonthlyAndLimits() {
        ZoneId z = Gst.ZONE;
        Instant first = LocalDate.of(2026, 1, 31).atTime(9, 0).atZone(z).toInstant();
        TimeSlot t = new TimeSlot(first, first.plusSeconds(1800));
        assertEquals(3, new RecurrenceRule(RecurrenceRule.Frequency.DAILY, 2, null, LocalDate.of(2026, 2, 4), null).expand(t, z).size());
        List<TimeSlot> monthly = new RecurrenceRule(RecurrenceRule.Frequency.MONTHLY, 1, null, null, 3).expand(t, z);   // 31st: Jan, Mar, May (no 31st in Feb/Apr)
        assertEquals(List.of(1, 3, 5), monthly.stream().map(x -> x.start().atZone(z).getMonthValue()).toList());
        assertThrows(ValidationException.class, () -> new RecurrenceRule(RecurrenceRule.Frequency.DAILY, 1, null, null, 53));    // max 52
        assertThrows(ValidationException.class, () -> new RecurrenceRule(RecurrenceRule.Frequency.DAILY, 1, null, LocalDate.of(2030, 1, 1), null).expand(t, z));
        assertThrows(ValidationException.class, () -> new RecurrenceRule(RecurrenceRule.Frequency.DAILY, 1, null, null, null));
    }

    @Test void passwordHashingIsSaltedAndVerifies() {              // NFR-SEC-01
        PasswordHasher h = new PasswordHasher(1000);
        PasswordHash a = h.hash("Secret123".toCharArray()), b = h.hash("Secret123".toCharArray());
        assertFalse(Arrays.equals(a.salt(), b.salt()));
        assertEquals(16, a.salt().length);
        assertTrue(h.verify("Secret123".toCharArray(), PasswordHash.decode(a.encode())));
        assertFalse(h.verify("secret123".toCharArray(), a));
        assertEquals(1000, PasswordHash.decode(a.encode()).iterations());
    }

    @Test void arabicNormalization() {                             // FR-SRC-06
        assertEquals(ArabicNormalizer.normalize("إِبْرَاهِيم"), ArabicNormalizer.normalize("ابراهيم"));
        assertEquals(ArabicNormalizer.normalize("مدرسة"), ArabicNormalizer.normalize("مدرسه"));
        assertEquals("hello 12", ArabicNormalizer.normalize("HeLLo ١٢"));
    }

    @Test void icsEscapesAndFoldsLines() {                         // FR-CAL-02, RFC 5545
        String ics = new IcsBuilder().event("uid@x", T0, T0, T0.plusSeconds(3600), "A;B,C\nD " + "x".repeat(100), "Hall", null).build();
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.contains("UID:uid@x\r\n"));
        assertTrue(ics.contains("DTSTART:20261110T060000Z\r\n"));
        for (String line : ics.split("\r\n")) assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75, "line too long: " + line);
        assertTrue(ics.contains("A\\;B\\,C\\nD"));
    }

    @Test void localizedTextFallsBack() {                          // FR-LOC-04
        LocalizedText t = new LocalizedText("Hello", "");
        assertEquals("Hello", t.get(Language.AR));
        assertEquals("مرحبا", new LocalizedText("", "مرحبا").get(Language.EN));
        assertEquals(t, LocalizedText.decode(t.encode()));
    }
}

/** Integration tests of registration, waitlist, clash detection and concurrency (NFR-MNT-04). Need the test database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationIT {
    TestEnv env;

    @BeforeAll void setUp() throws Exception { Assumptions.assumeTrue(TestEnv.available(), "test database not available"); env = new TestEnv(); }
    @AfterAll void tearDown() { if (env != null) env.close(); }

    private int seats(long eventId) { return env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.registrations.countSeatsHeld(eventId)); }

    @Test void concurrentRegistrationsNeverExceedCapacity() throws Exception {      // FR-REG-05, BR-04, R-03
        long ev = env.openEvent("Race", 3, 10, 2, env.venueBig, 5);
        int n = 30;
        List<UserSession> students = new ArrayList<>();
        for (int i = 0; i < n; i++) students.add(env.student());
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Registration>> fs = new ArrayList<>();
        for (UserSession s : students) fs.add(pool.submit(() -> { go.await(); return env.ctx.registrations.register(s, ev); }));
        go.countDown();
        int confirmed = 0, waitlisted = 0;
        for (Future<Registration> f : fs) { Registration r = f.get(60, TimeUnit.SECONDS); if (r.status() == RegistrationStatus.CONFIRMED) confirmed++; else waitlisted++; }
        pool.shutdown();
        assertEquals(5, confirmed);
        assertEquals(25, waitlisted);
        assertEquals(5, seats(ev));
        Set<Integer> positions = new HashSet<>();
        for (RegistrantRow r : env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.registrations.findRegistrants(ev, null)))
            if (r.waitlistPosition() > 0) positions.add(r.waitlistPosition());
        assertEquals(25, positions.size(), "waitlist positions must be unique");
    }

    @Test void concurrentCancellationsPromoteExactlyOncePerSeat() throws Exception {
        long ev = env.openEvent("Churn", 3, 14, 1, env.venueBig, 3);
        List<UserSession> s = new ArrayList<>();
        List<Registration> regs = new ArrayList<>();
        for (int i = 0; i < 8; i++) { UserSession x = env.student(); s.add(x); regs.add(env.ctx.registrations.register(x, ev)); }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) { final int k = i; fs.add(pool.submit(() -> env.ctx.registrations.cancel(s.get(k), regs.get(k).getId()))); }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(3, seats(ev));
        // students 3,4,5 were the first three on the waitlist (FIFO, BR-08)
        for (int i = 3; i < 6; i++) assertEquals(RegistrationStatus.CONFIRMED, status(regs.get(i).getId()));
        for (int i = 6; i < 8; i++) assertEquals(RegistrationStatus.WAITLISTED, status(regs.get(i).getId()));
    }

    private RegistrationStatus status(long regId) { return env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.registrations.findById(regId)).orElseThrow().status(); }

    @Test void waitlistIsFifoAndPromotionNotifies() {                               // FR-WTL-01..04
        long ev = env.openEvent("Fifo", 4, 10, 1, env.venueBig, 1);
        UserSession a = env.student(), b = env.student(), c = env.student();
        Registration ra = env.ctx.registrations.register(a, ev), rb = env.ctx.registrations.register(b, ev), rc = env.ctx.registrations.register(c, ev);
        assertEquals(RegistrationStatus.CONFIRMED, ra.status());
        assertEquals(RegistrationStatus.WAITLISTED, rb.status());
        assertEquals(2, env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.registrations.waitlistPosition(rc.getId())));
        env.ctx.registrations.cancel(a, ra.getId());
        assertEquals(RegistrationStatus.CONFIRMED, status(rb.getId()));
        assertEquals(RegistrationStatus.WAITLISTED, status(rc.getId()));
        assertTrue(env.ctx.notifications.list(b, 10).stream().anyMatch(n -> n.type().equals("NT-03")));
        assertEquals(1, env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.registrations.waitlistPosition(rc.getId())));
    }

    @Test void promotionSkipsStudentsWithAClashAndTellsThem() {                     // FR-WTL-03, NT-04
        long full = env.openEvent("Full", 5, 10, 2, env.venueBig, 1);
        UserSession holder = env.student(), clasher = env.student(), next = env.student();
        Registration rh = env.ctx.registrations.register(holder, full);
        Registration rc = env.ctx.registrations.register(clasher, full);                // waitlisted
        Registration rn = env.ctx.registrations.register(next, full);                   // waitlisted
        // a waitlisted student cannot register for an overlapping event directly (FR-CLS-01) ...
        long other = env.openEvent("Overlap", 5, 14, 2, env.venueSmall, 5);
        env.ctx.registrations.register(clasher, other);
        // ... but the other event can later be rescheduled so that it overlaps (clash appears only at promotion time)
        Event o = env.ctx.events.get(env.staff, other);
        EventData moved = EventService.toData(o);
        moved.startAt = env.at(5, 11); moved.endAt = env.at(5, 13);
        env.ctx.events.edit(env.staff, other, o.getVersion(), moved);
        env.ctx.registrations.cancel(holder, rh.getId());
        assertEquals(RegistrationStatus.WAITLISTED, status(rc.getId()), "clasher stays on the waitlist");
        assertEquals(RegistrationStatus.CONFIRMED, status(rn.getId()), "next student is promoted");
        assertTrue(env.ctx.notifications.list(clasher, 10).stream().anyMatch(n -> n.type().equals("NT-04")));
    }

    @Test void clashDetectionRefusesOverlapButAllowsBackToBack() {                  // FR-CLS-01..03
        long a = env.openEvent("A", 6, 10, 2, env.venueBig, 10);
        long overlap = env.openEvent("B", 6, 11, 2, env.venueSmall, 10);
        long adjacent = env.openEvent("C", 6, 12, 1, env.venueBig, 10);
        UserSession s = env.student();
        env.ctx.registrations.register(s, a);
        ScheduleClashException ex = assertThrows(ScheduleClashException.class, () -> env.ctx.registrations.register(s, overlap));
        assertEquals("error.clash", ex.getMessageKey());
        assertNotNull(env.ctx.registrations.previewClash(s, overlap).orElse(null));
        env.ctx.registrations.register(s, adjacent);                                     // back-to-back is fine
        assertThrows(AlreadyRegisteredException.class, () -> env.ctx.registrations.register(s, a));   // FR-REG-03
    }

    @Test void everyWorkshopSessionIsCheckedForClashes() {                           // FR-WSH-06
        EventData w = env.data("WS", 7, 10, 1, env.venueBig, 10);
        w.type = EventType.WORKSHOP; w.startAt = null; w.endAt = null;
        w.sessions = new ArrayList<>(List.of(new SessionData(null, LocalizedText.of("s1"), env.at(7, 10), env.at(7, 11), null), new SessionData(null, LocalizedText.of("s2"), env.at(9, 10), env.at(9, 11), null)));
        Event ws = env.ctx.events.create(env.staff, w);
        env.ctx.events.publish(env.staff, ws.getId());
        env.ctx.scheduler.tick();
        long second = env.openEvent("Same time as session 2", 9, 10, 1, env.venueSmall, 10);
        UserSession s = env.student();
        env.ctx.registrations.register(s, second);
        assertThrows(ScheduleClashException.class, () -> env.ctx.registrations.register(s, ws.getId()));
    }

    @Test void cancellationDeadlineIsEnforcedButOrganizersMayCancelAnytime() {      // FR-REG-06, FR-REG-11, BR-05
        long ev = env.openEvent("Deadline", 8, 10, 1, env.venueBig, 5);
        UserSession s = env.student(), s2 = env.student();
        Registration r = env.ctx.registrations.register(s, ev), r2 = env.ctx.registrations.register(s2, ev);
        env.clock.set(env.at(8, 10).minus(Duration.ofHours(23)));                       // inside the 24 h default deadline
        try {
            assertThrows(CancellationClosedException.class, () -> env.ctx.registrations.cancel(s, r.getId()));
            assertThrows(ValidationException.class, () -> env.ctx.registrations.cancelOnBehalf(env.staff, r2.getId(), " "));
            env.ctx.registrations.cancelOnBehalf(env.staff, r2.getId(), "student request");
            assertEquals(RegistrationStatus.CANCELLED, status(r2.getId()));
        } finally { env.clock.set(TestEnv.START); }
    }

    @Test void registrationRequiresAnOpenEventAndPermission() {                      // BR-12, BR-01, BR-02
        Event e = env.ctx.events.create(env.staff, env.data("Draft", 9, 10, 1, env.venueBig, 5));
        UserSession s = env.student();
        assertThrows(NotOpenException.class, () -> env.ctx.registrations.register(s, e.getId()));
        assertThrows(AuthorizationException.class, () -> env.ctx.registrations.register(env.admin, e.getId()));
        assertThrows(AuthorizationException.class, () -> env.ctx.registrations.register(env.staff, e.getId()));
    }
}

/** Integration tests of lifecycle, proposals, venues, series, authentication, check-in, results, feedback and search. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowIT {
    TestEnv env;

    @BeforeAll void setUp() throws Exception { Assumptions.assumeTrue(TestEnv.available(), "test database not available"); env = new TestEnv(); }
    @AfterAll void tearDown() { if (env != null) env.close(); }
    @AfterEach void resetClock() { if (env != null) env.clock.set(TestEnv.START); }
    @BeforeEach void longSessions() { env.ctx.sessions.setTimeout(Duration.ofDays(3650)); }   // login restores the 20-minute setting

    private Event event(long id) { return env.ctx.events.get(env.staff, id); }
    private boolean has(UserSession s, String type) { return env.ctx.notifications.list(s, 100).stream().anyMatch(n -> n.type().equals(type)); }

    @Test void schedulerDrivesTheLifecycleAndIsIdempotent() {                         // FR-LCY-07/08, NFR-REL-05
        EventData d = env.data("Life", 12, 10, 2, env.venueBig, 5);
        d.regOpensAt = env.at(10, 8);
        Event e = env.ctx.events.create(env.staff, d);
        env.ctx.events.publish(env.staff, e.getId());
        env.ctx.scheduler.tick();
        assertEquals(EventStatus.PUBLISHED, event(e.getId()).status());                // opens in two days
        env.clock.set(env.at(10, 9)); env.ctx.scheduler.tick();
        assertEquals(EventStatus.OPEN, event(e.getId()).status());
        UserSession s = env.student();
        env.ctx.registrations.register(s, e.getId());
        env.clock.set(env.at(11, 10)); var t = env.ctx.scheduler.tick();               // 24 h before: reminder
        assertTrue(has(s, "NT-08"));
        int reminders = env.ctx.notifications.list(s, 100).stream().filter(n -> n.type().equals("NT-08")).toList().size();
        env.ctx.scheduler.tick(); env.ctx.scheduler.tick();
        assertEquals(reminders, env.ctx.notifications.list(s, 100).stream().filter(n -> n.type().equals("NT-08")).toList().size(), "reminder only once");
        env.clock.set(env.at(12, 10)); env.ctx.scheduler.tick();                       // deadline == start: closes, then starts
        assertEquals(EventStatus.ONGOING, event(e.getId()).status());
        env.clock.set(env.at(12, 12)); env.ctx.scheduler.tick();
        assertEquals(EventStatus.COMPLETED, event(e.getId()).status());
        assertEquals(List.of(EventStatus.PUBLISHED, EventStatus.OPEN, EventStatus.ONGOING, EventStatus.COMPLETED),
                env.ctx.events.history(env.staff, e.getId()).stream().map(h -> h.to()).filter(x -> x != EventStatus.DRAFT && x != EventStatus.CLOSED).toList());
    }

    @Test void cancellingAnEventCancelsRegistrationsAndNotifies() {                   // FR-EVT-05, NT-06
        long ev = env.openEvent("Cancel me", 14, 10, 1, env.venueBig, 2);
        UserSession a = env.student(), b = env.student(), c = env.student();
        for (UserSession s : List.of(a, b, c)) env.ctx.registrations.register(s, ev);
        assertThrows(ValidationException.class, () -> env.ctx.events.cancel(env.staff, ev, ""));
        assertThrows(AuthorizationException.class, () -> env.ctx.events.cancel(a, ev, "x"));
        env.ctx.events.cancel(env.staff, ev, "Speaker ill");
        assertEquals(EventStatus.CANCELLED, event(ev).status());
        for (UserSession s : List.of(a, b, c)) assertTrue(has(s, "NT-06"));            // confirmed and waitlisted
        assertThrows(NotOpenException.class, () -> env.ctx.registrations.register(env.student(), ev));
    }

    @Test void venueCannotBeDoubleBooked() {                                          // BR-10, FR-VEN-03
        env.openEvent("First", 15, 10, 2, env.venueBig, 10);
        Event second = env.ctx.events.create(env.staff, env.data("Second", 15, 11, 2, env.venueBig, 10));
        VenueConflictException ex = assertThrows(VenueConflictException.class, () -> env.ctx.events.publish(env.staff, second.getId()));
        assertEquals("error.venue.conflict", ex.getMessageKey());
        Event third = env.ctx.events.create(env.staff, env.data("Back-to-back", 15, 12, 1, env.venueBig, 10));
        env.ctx.events.publish(env.staff, third.getId());                              // adjacent is fine
        Event tooBig = env.ctx.events.create(env.staff, env.data("Too big", 16, 10, 1, env.venueSmall, 50));
        assertThrows(ValidationException.class, () -> env.ctx.events.publish(env.staff, tooBig.getId()));   // capacity > venue
    }

    @Test void proposalWorkflowAndOfficerRights() {                                   // UC-08, FR-LCY-02..04, BR-14
        User officerUser = env.user(AccountType.STUDENT, "OFF1");
        UserSession officer = env.session(officerUser), other = env.student();
        Club c = new Club(); c.setName(LocalizedText.of("Chess Club"));
        c = env.ctx.clubs.create(env.staff, c, officerUser.getId());
        final long clubId = c.getId();
        EventData d = env.data("Chess Open", 17, 10, 2, env.venueBig, 20); d.hostClubId = clubId;
        assertThrows(AuthorizationException.class, () -> env.ctx.events.create(other, d));          // not an officer of that club
        Event p = env.ctx.events.create(officer, d);
        assertTrue(p.isProposal());
        assertThrows(ConflictException.class, () -> env.ctx.events.publish(env.staff, p.getId()));  // proposals go through approval
        env.ctx.events.submit(officer, p.getId());
        assertTrue(has(env.staff, "NT-09"));
        assertThrows(ValidationException.class, () -> env.ctx.events.reject(env.staff, p.getId(), " "));
        env.ctx.events.reject(env.staff, p.getId(), "Pick another date");
        assertEquals(EventStatus.DRAFT, event(p.getId()).status());
        assertTrue(has(officer, "NT-10"));
        env.ctx.events.submit(officer, p.getId());
        env.ctx.events.approve(env.staff, p.getId());
        assertEquals(EventStatus.PUBLISHED, event(p.getId()).status());
        assertThrows(AuthorizationException.class, () -> env.ctx.events.edit(officer, p.getId(), event(p.getId()).getVersion(), EventService0.data(event(p.getId()))));  // published: Staff only
        assertThrows(AuthorizationException.class, () -> env.ctx.events.cancel(officer, p.getId(), "x"));
    }

    @Test void staffCannotBeOfficersAndLastOfficerStays() {                           // BR-01, FR-CLB-02
        User t = env.user(AccountType.STAFF, "T9"), s = env.user(AccountType.STUDENT, "CLUBSTU");
        Club c = new Club(); c.setName(LocalizedText.of("Club X"));
        assertThrows(ValidationException.class, () -> env.ctx.clubs.create(env.staff, c, t.getId()));
        Club c2 = new Club(); c2.setName(LocalizedText.of("Club X"));
        Club made = env.ctx.clubs.create(env.staff, c2, s.getId());
        assertThrows(ConflictException.class, () -> env.ctx.clubs.removeOfficer(env.staff, made.getId(), s.getId()));
        assertThrows(ConflictException.class, () -> env.ctx.clubs.leave(env.session(s), made.getId()));
    }

    @Test void recurringSeriesIsAtomicAndReportsConflicts() {                         // FR-REC-03/04
        env.openEvent("Blocker", 22, 10, 1, env.venueSmall, 5);                        // occupies the 2nd weekly slot
        EventData d = env.data("Weekly", 15, 10, 1, env.venueSmall, 5);
        RecurrenceRule rule = new RecurrenceRule(RecurrenceRule.Frequency.WEEKLY, 1, Set.of(), null, 3);
        var preview = env.ctx.events.previewSeries(env.staff, d, rule);
        assertEquals(3, preview.size());
        assertTrue(preview.get(1).hasConflict());
        assertThrows(VenueConflictException.class, () -> env.ctx.events.createSeries(env.staff, d, rule, Set.of(), true));
        long before = count();
        List<Event> created = env.ctx.events.createSeries(env.staff, d, rule, Set.of(2), true);   // skip the conflicting date
        assertEquals(2, created.size());
        assertEquals(before + 2, count());
        Event first = created.get(0);
        env.ctx.events.cancelSeries(env.staff, first.getId(), "Term ended", SeriesScope.THIS_AND_FOLLOWING);
        for (Event e : created) assertEquals(EventStatus.CANCELLED, event(e.getId()).status());
        assertThrows(ValidationException.class, () -> env.ctx.events.createSeries(env.staff, env.data("Ws", 15, 10, 1, env.venueSmall, 5), rule, Set.of(1, 2, 3), false));
    }
    private long count() { return env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.search.search(new SearchCriteria() {{ visibility = Visibility.MANAGEMENT; includePast = true; }}, env.clock.instant()).total()); }

    @Test void temporaryPasswordMustBeChangedBeforeAnythingElse() {                  // FR-AUT-08
        User u = User.create(AccountType.STUDENT);
        u.setUniversityId("TEMP1"); u.setFullName(LocalizedText.of("Temp")); u.setEmail("t@x.example"); u.setMustChangePassword(true);
        u.setPasswordHash(env.ctx.hasher.hash("Temp0rary!".toCharArray()));
        env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.users.save(u));
        UserSession s = env.ctx.auth.login("TEMP1", "Temp0rary!".toCharArray());
        assertTrue(s.isMustChangePassword());
        assertThrows(AuthorizationException.class, () -> env.ctx.clubs.officerClubIds(s));
        assertThrows(AuthorizationException.class, () -> env.ctx.search.search(s, new SearchCriteria()));
        assertEquals("TEMP1", env.ctx.users.getProfile(s).getUniversityId());           // the profile page still works
        assertThrows(ValidationException.class, () -> env.ctx.auth.changePassword(s, "Temp0rary!".toCharArray(), "short".toCharArray()));
        assertThrows(InvalidCredentialsException.class, () -> env.ctx.auth.changePassword(s, "wrong".toCharArray(), "Better1234".toCharArray()));
        env.ctx.auth.changePassword(s, "Temp0rary!".toCharArray(), "Better1234".toCharArray());
        assertFalse(s.isMustChangePassword());
        env.ctx.search.search(s, new SearchCriteria());                                  // now allowed
        assertThrows(InvalidCredentialsException.class, () -> env.ctx.auth.login("TEMP1", "Temp0rary!".toCharArray()));
        env.ctx.auth.login("TEMP1", "Better1234".toCharArray());
    }

    @Test void loginLockoutAfterFiveFailuresAndSessionTimeout() {                     // FR-AUT-03/05/06
        env.user(AccountType.STUDENT, "LOCK1");
        for (int i = 0; i < 4; i++) {
            InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () -> env.ctx.auth.login("LOCK1", "wrong".toCharArray()));
            assertEquals("error.login.invalid", ex.getMessageKey());
        }
        assertThrows(AccountLockedException.class, () -> env.ctx.auth.login("LOCK1", "wrong".toCharArray()));
        assertThrows(AccountLockedException.class, () -> env.ctx.auth.login("LOCK1", "Passw0rd!".toCharArray()));   // even the right password
        assertEquals("error.login.invalid", assertThrows(InvalidCredentialsException.class, () -> env.ctx.auth.login("NOPE", "x".toCharArray())).getMessageKey());   // same message
        env.clock.advance(Duration.ofMinutes(16));
        UserSession s = env.ctx.auth.login("LOCK1", "Passw0rd!".toCharArray());
        env.ctx.sessions.setTimeout(Duration.ofMinutes(20));
        try {
            env.clock.advance(Duration.ofMinutes(21));
            assertThrows(SessionExpiredException.class, () -> env.ctx.notifications.unreadCount(s));
        } finally { env.ctx.sessions.setTimeout(Duration.ofDays(3650)); }
        UserSession s2 = env.ctx.auth.login("LOCK1", "Passw0rd!".toCharArray());
        env.ctx.auth.logout(s2);
        assertThrows(SessionExpiredException.class, () -> env.ctx.notifications.unreadCount(s2));
    }

    @Test void checkInResultsAndFeedbackFlow() {                                      // UC-13..15, FR-CHK, FR-CMP, FR-FBK, BR-09
        EventData d = env.data("Contest", 3, 10, 2, env.venueBig, 5); d.type = EventType.COMPETITION;
        Event e = env.ctx.events.create(env.staff, d); env.ctx.events.publish(env.staff, e.getId()); env.ctx.scheduler.tick();
        UserSession a = env.student(), b = env.student(), c = env.student();
        Registration ra = env.ctx.registrations.register(a, e.getId()), rb = env.ctx.registrations.register(b, e.getId());
        env.ctx.registrations.register(c, e.getId());
        assertThrows(ValidationException.class, () -> env.ctx.checkIn.markPresent(env.staff, ra.getId(), null));   // too early (> 30 min before)
        env.clock.set(env.at(3, 10).plus(Duration.ofMinutes(5))); env.ctx.scheduler.tick();
        assertEquals(EventStatus.ONGOING, event(e.getId()).status());
        env.ctx.checkIn.markPresent(env.staff, ra.getId(), null);
        env.ctx.checkIn.markPresent(env.staff, rb.getId(), null);
        env.ctx.checkIn.markAbsent(env.staff, rb.getId(), null);                       // undo is logged
        assertThrows(AuthorizationException.class, () -> env.ctx.checkIn.markPresent(a, ra.getId(), null));
        var view = env.ctx.checkIn.attendanceList(env.staff, e.getId(), null);
        assertEquals(1, view.present()); assertEquals(2, view.absent());
        // results: only attended students can be placed; draft is invisible until published
        assertThrows(ValidationException.class, () -> env.ctx.results.saveDraft(env.staff, e.getId(), List.of(new ResultService.Entry(rb.getUserId(), 1, null))));
        env.ctx.results.saveDraft(env.staff, e.getId(), List.of(new ResultService.Entry(ra.getUserId(), 1, new BigDecimal("98.5"))));
        assertThrows(AuthorizationException.class, () -> env.ctx.results.results(a, e.getId()));
        env.ctx.results.publish(env.staff, e.getId());
        assertEquals(1, env.ctx.results.results(b, e.getId()).size());
        assertTrue(has(a, "NT-13"));
        assertThrows(ConflictException.class, () -> env.ctx.results.saveDraft(env.staff, e.getId(), List.of()));      // published: correct only
        env.ctx.results.correct(env.staff, e.getId(), List.of(new ResultService.Entry(ra.getUserId(), 2, null)));
        assertEquals(2, env.ctx.results.myPlacements(a).get(0).getPlacement());
        // feedback only after completion, once, attended only
        assertThrows(ConflictException.class, () -> env.ctx.feedback.submit(a, e.getId(), 5, "great"));
        env.clock.set(env.at(3, 13)); env.ctx.scheduler.tick();
        assertEquals(EventStatus.COMPLETED, event(e.getId()).status());
        assertTrue(has(a, "NT-14")); assertFalse(has(b, "NT-14"));
        assertThrows(ValidationException.class, () -> env.ctx.feedback.submit(a, e.getId(), 6, null));
        env.ctx.feedback.submit(a, e.getId(), 4, "good");
        assertThrows(ConflictException.class, () -> env.ctx.feedback.submit(a, e.getId(), 5, null));                    // one per student
        assertThrows(ConflictException.class, () -> env.ctx.feedback.submit(c, e.getId(), 5, null));                    // did not attend
        assertEquals(FeedbackService.Status.CAN_EDIT, env.ctx.feedback.status(a, e.getId()));
        var staffView = env.ctx.feedback.summary(env.staff, e.getId());
        assertEquals(4.0, staffView.average()); assertEquals(1, staffView.comments().size()); assertTrue(staffView.namesVisible());
        assertTrue(env.ctx.feedback.summary(b, e.getId()).comments().isEmpty());                                         // students see only the average
        env.clock.advance(Duration.ofDays(8));
        assertThrows(ConflictException.class, () -> env.ctx.feedback.edit(a, e.getId(), 5, "x"));                        // edit window is 7 days
    }

    @Test void searchRecommendationsDiscussionAndCalendar() {                         // UC-03/04, FR-SRC, FR-RCM, FR-DSC, FR-CAL
        UserSession s = env.student();
        EventData d = env.data("Ibrahim Robotics مختبر الروبوتات", 4, 10, 1, env.venueBig, 20);
        Event e = env.ctx.events.create(env.staff, d); env.ctx.events.publish(env.staff, e.getId()); env.ctx.scheduler.tick();
        SearchCriteria c = new SearchCriteria(); c.keyword = "ROBOTICS";
        assertTrue(env.ctx.search.search(s, c).items().stream().anyMatch(x -> x.event().getId() == e.getId()));
        c.keyword = "الروبوتات"; assertFalse(env.ctx.search.search(null, c).items().isEmpty());                       // guests too, Arabic
        c.keyword = "ibrahim zzzz"; assertTrue(env.ctx.search.search(s, c).items().isEmpty());                         // all words must match
        c = new SearchCriteria(); c.keyword = "ibrahim"; c.seatsAvailableOnly = true; c.openOnly = true;
        assertEquals(1, env.ctx.search.search(s, c).total());
        // no profile -> popular events with reason; with interest -> rule score
        var recs = env.ctx.recommendations.recommend(s);
        assertFalse(recs.isEmpty()); assertEquals("reason.popular", recs.get(0).reason().key());
        assertThrows(AuthorizationException.class, () -> env.ctx.recommendations.recommend(env.staff));
        // discussion
        var top = env.ctx.discussion.post(s, e.getId(), "Is parking available?", null);
        var reply = env.ctx.discussion.post(env.staff, e.getId(), "Yes, lot B.", top.getId());
        assertTrue(has(s, "NT-16"));
        assertThrows(ValidationException.class, () -> env.ctx.discussion.post(s, e.getId(), "nested", reply.getId()));    // one level only
        assertThrows(AuthorizationException.class, () -> env.ctx.discussion.edit(env.student(), top.getId(), "hack"));
        assertThrows(ValidationException.class, () -> env.ctx.discussion.moderate(env.staff, top.getId(), ""));
        env.ctx.discussion.moderate(env.staff, top.getId(), "Off topic");
        assertTrue(env.ctx.discussion.list(s, e.getId()).get(0).isRemoved());
        // calendar export
        env.ctx.registrations.register(s, e.getId());
        String ics = env.ctx.calendar.exportMine(s);
        assertTrue(ics.contains("UID:uems-event-" + e.getId() + "@uems.local"));
        assertTrue(ics.contains("BEGIN:VEVENT") && ics.endsWith("END:VCALENDAR\r\n"));
    }

    /** Small helper so tests can read event data without importing the service class everywhere. */
    private static final class EventService0 { static EventData data(Event e) { return EventService.toData(e); } }
}

/** University number rules: students have 9 digits (registration year 20xx + 5 digits); other accounts are free text. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserIdIT {
    TestEnv env;

    @BeforeAll void setUp() throws Exception { Assumptions.assumeTrue(TestEnv.available(), "test database not available"); env = new TestEnv(); }
    @AfterAll void tearDown() { if (env != null) env.close(); }

    private UserData student(String id) {
        UserData d = new UserData();
        d.universityId = id; d.fullName = LocalizedText.of("Test Student"); d.email = "t@x.example"; d.accountType = AccountType.STUDENT;
        return d;
    }

    @Test void patternAcceptsOnlyNineDigitsStartingWith20() {
        for (String ok : new String[]{"202612345", "200000000", "209999999"}) assertTrue(UserService.STUDENT_ID.matcher(ok).matches(), ok);
        for (String bad : new String[]{"S2026001", "20261234", "2026123456", "192612345", "301234567", "2026 2345", "20261234a", ""})
            assertFalse(UserService.STUDENT_ID.matcher(bad).matches(), bad);
    }

    @Test void studentWithWrongFormatIsRefused() {
        assertEquals("error.user.idFormat", assertThrows(ValidationException.class, () -> env.ctx.users.create(env.admin, student("S2026001"))).getMessageKey());
        assertThrows(ValidationException.class, () -> env.ctx.users.create(env.admin, student("12345")));
    }

    @Test void validStudentIdIsAccepted() {
        env.ctx.users.create(env.admin, student("202401234"));
        assertThrows(ConflictException.class, () -> env.ctx.users.create(env.admin, student("202401234")));   // duplicate
    }

    @Test void blankStudentIdIsGeneratedAsCurrentYearPlusFiveRandomDigits() {
        Set<String> seen = new HashSet<>();
        int year = java.time.LocalDate.now(Gst.ZONE).getYear();
        for (int i = 0; i < 25; i++) {
            UserData d = student("");
            String temp = env.ctx.users.create(env.admin, d);
            assertNotNull(temp);
            assertTrue(UserService.STUDENT_ID.matcher(d.universityId).matches(), d.universityId);
            assertTrue(d.universityId.startsWith(String.valueOf(year)));
            assertTrue(seen.add(d.universityId), "IDs must be unique");
            assertTrue(env.ctx.persistence.tx.inTransaction(() -> env.ctx.persistence.users.findByUniversityId(d.universityId)).isPresent());
        }
    }

    @Test void staffAndAdminIdsStayFreeText() {
        UserData d = student("T5001"); d.accountType = AccountType.STAFF;
        env.ctx.users.create(env.admin, d);
        d = student(""); d.accountType = AccountType.STAFF;
        UserData blank = d;
        assertEquals("error.user.idRequired", assertThrows(ValidationException.class, () -> env.ctx.users.create(env.admin, blank)).getMessageKey());   // no generation for staff
    }

    @Test void csvImportChecksFormatAndGeneratesBlankStudentIds() {
        int n = env.ctx.users.importCsv(env.admin, "# id,nameEn,nameAr,email,type,faculty\n202511111,Ali,علي,ali@x.example,STUDENT,IT\n,Sara,سارة,sara@x.example,STUDENT,Arts\nT7001,Omar,,omar@x.example,STAFF,\n");
        assertEquals(3, n);
        ValidationException ex = assertThrows(ValidationException.class, () -> env.ctx.users.importCsv(env.admin, "S12345,Bad,,bad@x.example,STUDENT,IT"));
        assertEquals("error.import.line", ex.getMessageKey());
    }

    @Test void changingAnExistingStudentWithLegacyIdIsAllowedUnlessTheIdChanges() {
        User legacy = env.user(AccountType.STUDENT, "LEGACY1");        // created directly, like data from before the rule
        UserData d = student("LEGACY1"); d.fullName = LocalizedText.of("Renamed");
        env.ctx.users.update(env.admin, legacy.getId(), d);            // name change only: fine
        UserData d2 = student("LEGACY2");
        assertThrows(ValidationException.class, () -> env.ctx.users.update(env.admin, legacy.getId(), d2));   // changing to a bad ID: refused
        env.ctx.users.update(env.admin, legacy.getId(), student("202300001"));                                // changing to a good ID: fine
    }
}

/** Manual smoke test: drives the real Swing UI under a virtual display and saves screenshots (see docs/TESTING.md). */
final class UiSmoke {
    static MainFrame frame; static UiContext ctx; static int n;
    static String dir = "/tmp/shots";

    public static void main(String[] a) throws Exception {
        new File(dir).mkdirs();
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        for (Object k : java.util.Collections.list(UIManager.getDefaults().keys())) if (k.toString().endsWith(".font")) UIManager.put(k, new javax.swing.plaf.FontUIResource("Dialog", Font.PLAIN, 14));
        AppContext app = AppContext.create(Config.load(java.nio.file.Path.of("uems.properties")));
        ctx = new UiContext(app);
        SwingUtilities.invokeAndWait(() -> { frame = new MainFrame(ctx); frame.setVisible(true); });
        shot("login-en");
        on(() -> ctx.setLanguage(Language.AR)); shot("login-ar");
        on(() -> ctx.setLanguage(Language.EN));
        on(() -> ctx.go(() -> new BrowseScreen(ctx))); shot("guest-browse");
        login("202610004");
        shot("student-home");
        for (String[] s : new String[][]{{"browse"}, {"myreg"}, {"clubs"}, {"notif"}, {"profile"}}) { on(() -> ctx.open(() -> screen(s[0]))); shot("student-" + s[0]); }
        on(() -> ctx.go(() -> new EventDetailScreen(ctx, eventId("Football Friendly")))); shot("student-event");
        on(() -> ctx.setLanguage(Language.AR)); shot("student-event-ar");
        for (String s : new String[]{"home", "browse", "myreg", "clubs", "notif"}) { on(() -> ctx.open(() -> screen(s))); shot("student-ar-" + s); }
        on(() -> ctx.setLanguage(Language.EN));
        logout(); login("T1001");
        shot("staff-home");
        on(() -> ctx.open(() -> new ManageEventsScreen(ctx))); shot("staff-manage");
        on(() -> ctx.open(() -> new ManageEventsScreen(ctx, true))); shot("staff-proposals");
        on(() -> ctx.go(() -> new EventDetailScreen(ctx, eventId("Cultural Night")))); shot("staff-proposal-detail");
        on(() -> ctx.go(() -> new EventEditorScreen(ctx))); shot("staff-editor");
        on(() -> ctx.open(() -> new ClubsAdminScreen(ctx))); shot("staff-clubs");
        // dialogs (modal): open on the EDT, capture the screen, then dispose
        long welcome = eventId("Welcome Week Fair"), robo = eventId("Robotics Challenge");
        on(() -> ctx.go(() -> new EventDetailScreen(ctx, welcome))); Thread.sleep(800);
        for (String d : new String[]{"registrants", "attendance", "announce", "history"}) dialog(d, welcome);
        on(() -> ctx.go(() -> new EventDetailScreen(ctx, robo))); Thread.sleep(800);
        dialog("results", robo);
        logout(); login("202610004");
        on(() -> ctx.open(() -> new ClubsScreen(ctx))); Thread.sleep(800);
        javax.swing.SwingUtilities.invokeLater(() -> { try { var m = ClubsScreen.class.getDeclaredMethod("details", ClubInfo.class); m.setAccessible(true);
            var cs = (ClubsScreen) java.util.Arrays.stream(frame.getContentPane().getComponents()).flatMap(c -> c instanceof JPanel p ? java.util.Arrays.stream(p.getComponents()) : java.util.stream.Stream.of(c)).filter(c -> c instanceof ClubsScreen).findFirst().orElseThrow();
            m.invoke(cs, ctx.app.clubs.list(ctx.session(), true).get(0)); } catch (Exception e) { e.printStackTrace(); } });
        Thread.sleep(1500); grab("club-details-dialog"); closeDialogs();
        logout(); login("202610001");
        on(() -> ctx.open(() -> new ClubManageScreen(ctx, ctx.officerClubs().get(0)))); shot("officer-club");
        logout();
        var s = app.auth.login("admin", "ChangeMe123".toCharArray());
        on(() -> frame.setSessionAndContinue(s)); Thread.sleep(1200); shot("admin-forced-password");
        app.auth.changePassword(s, "ChangeMe123".toCharArray(), "NewAdmin2026".toCharArray());
        on(() -> frame.passwordChanged()); Thread.sleep(800); shot("admin-home");
        on(() -> ctx.open(() -> new UsersScreen(ctx))); shot("admin-users");
        SwingUtilities.invokeLater(() -> { try { var m = UsersScreen.class.getDeclaredMethod("edit", User.class); m.setAccessible(true);
            var us = java.util.Arrays.stream(frame.getContentPane().getComponents()).flatMap(c -> c instanceof JPanel p ? java.util.Arrays.stream(p.getComponents()) : java.util.stream.Stream.of(c)).filter(c -> c instanceof UsersScreen).findFirst().orElseThrow();
            m.invoke(us, (Object) null); } catch (Exception e) { e.printStackTrace(); } });
        Thread.sleep(1500); grab("dialog-new-user"); closeDialogs();
        on(() -> ctx.open(() -> new VenuesScreen(ctx))); shot("admin-venues");
        on(() -> ctx.open(() -> new AuditScreen(ctx))); shot("admin-audit");
        on(() -> ctx.setLanguage(Language.AR)); shot("admin-audit-ar");
        on(() -> ctx.setLanguage(Language.EN));
        on(() -> ctx.open(() -> new ManageEventsScreen(ctx))); shot("admin-forbidden-check");
        System.out.println("screenshots in " + dir);
        System.exit(0);
    }

    static Screen screen(String k) {
        return switch (k) { case "home" -> new HomeScreen(ctx); case "browse" -> new BrowseScreen(ctx); case "myreg" -> new MyRegistrationsScreen(ctx);
            case "clubs" -> new ClubsScreen(ctx); case "notif" -> new NotificationsScreen(ctx); default -> new ProfileScreen(ctx, false); };
    }
    static long eventId(String title) {
        return ctx.app.persistence.tx.inTransaction(() -> ctx.app.search.search(ctx.session() == null ? null : ctx.session(), new SearchCriteria() {{ keyword = title; includePast = true; }}).items().size() > 0 ? 0L : 0L) == 0L
                ? ctx.app.persistence.tx.inTransaction(() -> lookup(title)) : 0L;
    }
    static long lookup(String title) {
        var c = new SearchCriteria(); c.keyword = title; c.includePast = true; c.visibility = SearchCriteria.Visibility.MANAGEMENT;
        return ctx.app.persistence.search.search(c, java.time.Instant.now()).items().get(0).event().getId();
    }
    static void dialog(String kind, long id) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                var e = ctx.app.events.get(ctx.session(), id);
                Component owner = frame;
                var cls = Class.forName("uems.EventDialogs");
                switch (kind) {
                    case "registrants" -> call(cls, "registrants", owner, e, (Runnable) () -> { });
                    case "attendance" -> call(cls, "attendance", owner, e);
                    case "announce" -> call(cls, "announce", owner, e);
                    case "history" -> call(cls, "history", owner, e);
                    default -> call(cls, "results", owner, e);
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        });
        Thread.sleep(1800); grab("dialog-" + kind); closeDialogs();
    }
    static void call(Class<?> cls, String name, Object... args) throws Exception {
        for (var m : cls.getDeclaredMethods()) if (m.getName().equals(name)) { m.setAccessible(true); m.invoke(null, new Object[]{ctx}[0] == null ? null : prepend(args)); return; }
    }
    static Object[] prepend(Object[] a) { Object[] r = new Object[a.length + 1]; r[0] = ctx; System.arraycopy(a, 0, r, 1, a.length); return r; }
    static void grab(String name) throws Exception {
        BufferedImage img = new Robot().createScreenCapture(new Rectangle(0, 0, 1280, 800));
        ImageIO.write(img, "png", new File(dir, String.format("%02d-%s.png", ++n, name)));
    }
    static void closeDialogs() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isVisible()) d.dispose(); });
        Thread.sleep(400);
    }
    static void login(String id) throws Exception {
        var s = ctx.app.auth.login(id, "Demo1234".toCharArray());
        on(() -> frame.setSessionAndContinue(s)); Thread.sleep(1500);
    }
    static void logout() throws Exception { on(() -> frame.logout()); Thread.sleep(300); }
    static void on(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); Thread.sleep(900); }
    static void shot(String name) throws Exception {
        Thread.sleep(700);
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> frame.paint(img.getGraphics()));
        ImageIO.write(img, "png", new File(dir, String.format("%02d-%s.png", ++n, name)));
    }
}
