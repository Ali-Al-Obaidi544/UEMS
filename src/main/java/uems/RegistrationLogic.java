package uems;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static uems.DomainEvent.spec;
import static uems.Permission.*;

// MODULE 3 - REGISTRATION AND ATTENDANCE (logic)
// Contains the domain classes for registrations and notifications, their repositories, and the services for registration, waitlist, schedule-clash detection, check-in, notifications and calendar export.
// Layers in this file, in order: INFRASTRUCTURE (module 1 only), DOMAIN, PERSISTENCE, SERVICES.

// ========== DOMAIN ==========

/** A detected schedule clash: the conflicting event and the overlapping slot (FR-CLS-02). */
record Clash(long eventId, LocalizedText eventTitle, TimeSlot slot) {}

/** Strategy: decides whether a student's active registrations overlap the slots of a target event (SUC-1). */
interface ClashDetectionStrategy {
    /**
     * Read-only check. The target event itself is ignored (a waitlisted student must not clash with the
     * event being promoted into). Back-to-back events are not clashes (BR-07).
     */
    Optional<Clash> findClash(long studentId, long targetEventId, List<TimeSlot> target);
}

/** A student's registration with its event and waitlist position (FR-REG-08). Position is 0 unless Waitlisted. */
record MyRegistration(Registration registration, Event event, int waitlistPosition) {}

/** A registration together with the student's identity, for organizer screens (FR-REG-11). */
record RegistrantRow(Registration registration, String universityId, LocalizedText name, int waitlistPosition) {}

/** Links one student to one event with a status (glossary). Owns its {@link RegistrationState}. */
class Registration {
    private long id;
    private long eventId;
    private long userId;
    private RegistrationState state;
    private Instant registeredAt;
    private Instant cancelledAt;
    private Long cancelledBy;
    private String cancelReason;
    private Instant attendedAt;
    private Long checkedInBy;

    public Registration() { this.state = RegistrationStates.of(RegistrationStatus.CONFIRMED); }

    /** New registration that holds a seat. */
    public static Registration confirmed(long eventId, long userId, Instant now) { return create(eventId, userId, now, RegistrationStatus.CONFIRMED); }
    /** New registration at the end of the waitlist (FR-REG-10). */
    public static Registration waitlisted(long eventId, long userId, Instant now) { return create(eventId, userId, now, RegistrationStatus.WAITLISTED); }
    private static Registration create(long eventId, long userId, Instant now, RegistrationStatus s) {
        Registration r = new Registration();
        r.eventId = eventId; r.userId = userId; r.registeredAt = now; r.restoreState(s);
        return r;
    }

    void changeState(RegistrationState s) { this.state = s; }
    public void restoreState(RegistrationStatus s) { this.state = RegistrationStates.of(s); }
    public RegistrationStatus status() { return state.status(); }

    /** Cancels a Confirmed or Waitlisted registration. */
    public void cancel(Instant now, Long by, String reason) {
        state.cancel(this);
        this.cancelledAt = now; this.cancelledBy = by; this.cancelReason = reason;
    }
    /** Waitlisted becomes Confirmed (FR-WTL-04). */
    public void promote() { state.promote(this); }
    /** Confirmed becomes Attended (FR-CHK-03). */
    public void markAttended(Instant now, long by) { state.attend(this); this.attendedAt = now; this.checkedInBy = by; }
    /** Attended becomes Confirmed again (FR-CHK-05). */
    public void undoAttendance() { state.undoAttendance(this); this.attendedAt = null; this.checkedInBy = null; }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long e) { this.eventId = e; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant t) { this.registeredAt = t; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant t) { this.cancelledAt = t; }
    public Long getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(Long u) { this.cancelledBy = u; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String r) { this.cancelReason = r; }
    public Instant getAttendedAt() { return attendedAt; }
    public void setAttendedAt(Instant t) { this.attendedAt = t; }
    public Long getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(Long u) { this.checkedInBy = u; }
}

/** State pattern for registrations: Confirmed, Waitlisted, Cancelled, Attended. Illegal transitions are rejected. */
interface RegistrationState {
    RegistrationStatus status();
    void cancel(Registration r);
    void promote(Registration r);
    void attend(Registration r);
    void undoAttendance(Registration r);
}

/** The four concrete registration states and a factory from the persistent status. */
final class RegistrationStates {
    private RegistrationStates() {}

    public static RegistrationState of(RegistrationStatus s) {
        return switch (s) {
            case CONFIRMED -> new Confirmed();
            case WAITLISTED -> new Waitlisted();
            case CANCELLED -> new Cancelled();
            case ATTENDED -> new Attended();
        };
    }

    private abstract static class Base implements RegistrationState {
        IllegalTransitionException illegal(String a) { return new IllegalTransitionException("error.registration.transition", a, status().name()); }
        @Override public void cancel(Registration r) { throw illegal("cancel"); }
        @Override public void promote(Registration r) { throw illegal("promote"); }
        @Override public void attend(Registration r) { throw illegal("attend"); }
        @Override public void undoAttendance(Registration r) { throw illegal("undoAttendance"); }
    }
    static final class Confirmed extends Base {
        @Override public RegistrationStatus status() { return RegistrationStatus.CONFIRMED; }
        @Override public void cancel(Registration r) { r.changeState(new Cancelled()); }
        @Override public void attend(Registration r) { r.changeState(new Attended()); }
    }
    static final class Waitlisted extends Base {
        @Override public RegistrationStatus status() { return RegistrationStatus.WAITLISTED; }
        @Override public void cancel(Registration r) { r.changeState(new Cancelled()); }
        @Override public void promote(Registration r) { r.changeState(new Confirmed()); }
    }
    static final class Cancelled extends Base {
        @Override public RegistrationStatus status() { return RegistrationStatus.CANCELLED; }
    }
    static final class Attended extends Base {
        @Override public RegistrationStatus status() { return RegistrationStatus.ATTENDED; }
        @Override public void undoAttendance(Registration r) { r.changeState(new Confirmed()); }
    }
}

/** Registration status (glossary). Confirmed and Attended both hold a seat. */
enum RegistrationStatus {
    CONFIRMED, WAITLISTED, CANCELLED, ATTENDED;
    /** Active = counts for duplicate and clash checks (FR-REG-03, FR-CLS-01). */
    public boolean isActive() { return this != CANCELLED; }
    /** True when the registration holds a seat. */
    public boolean holdsSeat() { return this == CONFIRMED || this == ATTENDED; }
}

/** One audit-log row (FR-USR-08). {@code actorName} is filled only when reading. */
record AuditEntry(long id, Long actorId, String actorName, String action, String target, String details, Instant at) {}

/** A stored in-app notification. Text is produced at display time in the reader's language. */
record Notification(long id, long userId, String type, String templateKey, List<String> params,
                           String link, Instant createdAt, boolean read) {}

/** What to tell whom: language-independent template key and parameters (FR-NTF-05). {@code link} is e.g. "event:12". */
record NotificationSpec(long userId, String type, String templateKey, List<String> params, String link) {}

/** Encodes notification parameters so they can be rendered in the reader's language later. */
final class Params {
    private Params() {}
    /** A bilingual text parameter. */
    public static String text(LocalizedText t) { return t.encode(); }
    /** A point in time, formatted by the reader's client in Gulf Standard Time. */
    public static String instant(Instant t) { return "\u0003" + t.toEpochMilli(); }
    /** A resource-bundle key, translated by the reader's client. */
    public static String key(String bundleKey) { return "\u0004" + bundleKey; }
    /** Decodes an instant parameter or returns null. */
    public static Instant decodeInstant(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\u0003' ? Instant.ofEpochMilli(Long.parseLong(s.substring(1))) : null;
    }
    /** Decodes a bundle-key parameter or returns null. */
    public static String decodeKey(String s) { return s != null && !s.isEmpty() && s.charAt(0) == '\u0004' ? s.substring(1) : null; }
}

/** An organizer undid a check-in (audit, FR-CHK-05). */
record CheckInChanged(long actorId, long registrationId, String action) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, action, "registration:" + registrationId, null); }
}

/** NT-04: a seat became free but promotion was skipped because of a clash (FR-WTL-03). */
record PromotionSkipped(long userId, long eventId, LocalizedText title) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-04", "notif.waitlist.skipped", "event:" + eventId, Params.text(title)));
    }
}

/** NT-05: registration cancelled by the student or on the student's behalf; audited when done by an organizer. */
record RegistrationCancelled(long userId, long eventId, LocalizedText title, Long byOrganizerId, String reason) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        if (byOrganizerId == null)
            return List.of(spec(userId, "NT-05", "notif.registration.cancelled", "event:" + eventId, Params.text(title)));
        return List.of(spec(userId, "NT-05", "notif.registration.cancelledBy", "event:" + eventId, Params.text(title), reason == null ? "" : reason));
    }
    @Override public AuditSpec audit() {
        return byOrganizerId == null ? null : new AuditSpec(byOrganizerId, "REGISTRATION_CANCEL_BEHALF", "event:" + eventId + " user:" + userId, reason);
    }
}

/** NT-01: registration confirmed. */
record RegistrationConfirmed(long userId, long eventId, LocalizedText title) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-01", "notif.registration.confirmed", "event:" + eventId, Params.text(title)));
    }
}

/** NT-02: placed on the waitlist, with position. */
record RegistrationWaitlisted(long userId, long eventId, LocalizedText title, int position) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-02", "notif.registration.waitlisted", "event:" + eventId, Params.text(title), String.valueOf(position)));
    }
}

/** NT-03: promoted from the waitlist. */
record WaitlistPromoted(long userId, long eventId, LocalizedText title) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-03", "notif.waitlist.promoted", "event:" + eventId, Params.text(title)));
    }
}

/** Persistence contract for in-app notifications. */
interface NotificationRepository {
    void save(NotificationSpec spec, Instant at);
    List<Notification> findByUser(long userId, int limit);
    int countUnread(long userId);
    void markRead(long notificationId, long userId);
    void markAllRead(long userId);
    void delete(long notificationId, long userId);
    /** Deletes notifications older than the instant (FR-NTF-08); returns the number removed. */
    int purgeOlderThan(Instant before);
}

/** Persistence contract for registrations, waitlists and attendance. */
interface RegistrationRepository {
    Registration save(Registration r);
    Optional<Registration> findById(long id);
    /** The student's Confirmed, Waitlisted or Attended registration for the event, if any. */
    Optional<Registration> findActive(long eventId, long userId);
    /** Seats held: Confirmed plus Attended. */
    int countSeatsHeld(long eventId);
    int countWaitlisted(long eventId);
    /** Waitlisted registrations first-in-first-out (BR-08). */
    List<Registration> findWaitlist(long eventId);
    List<RegistrantRow> findRegistrants(long eventId, String search);
    List<MyRegistration> findByUser(long userId);
    int waitlistPosition(long registrationId);
    /** event id -> {seats held, waitlisted, attended}. */
    Map<Long, int[]> countsFor(Collection<Long> eventIds);
    List<Long> findUserIds(long eventId, RegistrationStatus... statuses);
    /** Cancels every active registration of the event; returns the affected user ids. */
    List<Long> cancelAllActive(long eventId, Instant at, long by, String reason);
    void saveAttendance(long registrationId, long sessionId, boolean present, Instant at, long by);
    void deleteAttendance(long registrationId);
    /** registration id -> ids of sessions the student attended. */
    Map<Long, Set<Long>> findPresentSessions(long eventId);
    Set<Long> findAttendedCategoryIds(long userId);
}

// ========== PERSISTENCE ==========

/** JDBC implementation of {@link NotificationRepository}. Parameters are stored separated by the unit-separator character. */
class JdbcNotificationRepository implements NotificationRepository {
    private static final String SEP = "\u001F";
    private final Jdbc db;
    public JdbcNotificationRepository(Jdbc db) { this.db = db; }

    @Override public void save(NotificationSpec s, Instant at) {
        db.exec("INSERT INTO notification (user_id, ntype, template_key, params, link, created_at) VALUES (?,?,?,?,?,?)",
                s.userId(), s.type(), s.templateKey(), String.join(SEP, s.params()), s.link(), at);
    }
    @Override public List<Notification> findByUser(long userId, int limit) {
        return db.list("SELECT notification_id, user_id, ntype, template_key, params, link, created_at, is_read FROM notification WHERE user_id=? ORDER BY created_at DESC, notification_id DESC LIMIT ?",
                rs -> new Notification(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5).isEmpty() ? List.of() : Arrays.asList(rs.getString(5).split(SEP, -1)), rs.getString(6), Jdbc.instant(rs, "created_at"), rs.getBoolean(8)),
                userId, limit);
    }
    @Override public int countUnread(long userId) { return (int) db.count("SELECT count(*) FROM notification WHERE user_id=? AND NOT is_read", userId); }
    @Override public void markRead(long id, long userId) { db.exec("UPDATE notification SET is_read=TRUE WHERE notification_id=? AND user_id=?", id, userId); }
    @Override public void markAllRead(long userId) { db.exec("UPDATE notification SET is_read=TRUE WHERE user_id=? AND NOT is_read", userId); }
    @Override public void delete(long id, long userId) { db.exec("DELETE FROM notification WHERE notification_id=? AND user_id=?", id, userId); }
    @Override public int purgeOlderThan(Instant before) { return db.exec("DELETE FROM notification WHERE created_at<?", before); }
}

/** JDBC implementation of {@link RegistrationRepository}. */
class JdbcRegistrationRepository implements RegistrationRepository {
    private static final String COLS = "registration_id, event_id, user_id, status, registered_at, cancelled_at, cancelled_by, cancel_reason, attended_at, checked_in_by";
    private final Jdbc db;
    private final JdbcEventRepository events;
    public JdbcRegistrationRepository(Jdbc db, JdbcEventRepository events) { this.db = db; this.events = events; }

    private static Registration map(ResultSet rs) throws SQLException {
        Registration r = new Registration();
        r.setId(rs.getLong("registration_id")); r.setEventId(rs.getLong("event_id")); r.setUserId(rs.getLong("user_id"));
        r.restoreState(RegistrationStatus.valueOf(rs.getString("status")));
        r.setRegisteredAt(Jdbc.instant(rs, "registered_at")); r.setCancelledAt(Jdbc.instant(rs, "cancelled_at"));
        r.setCancelledBy(Jdbc.longOrNull(rs, "cancelled_by")); r.setCancelReason(rs.getString("cancel_reason"));
        r.setAttendedAt(Jdbc.instant(rs, "attended_at")); r.setCheckedInBy(Jdbc.longOrNull(rs, "checked_in_by"));
        return r;
    }

    @Override public Registration save(Registration r) {
        try {
            if (r.getId() == 0) {
                r.setId(db.insert("INSERT INTO registration (event_id, user_id, status, registered_at, cancelled_at, cancelled_by, cancel_reason, attended_at, checked_in_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?) RETURNING registration_id", r.getEventId(), r.getUserId(), r.status(), r.getRegisteredAt(),
                        r.getCancelledAt(), r.getCancelledBy(), r.getCancelReason(), r.getAttendedAt(), r.getCheckedInBy()));
            } else {
                db.exec("UPDATE registration SET status=?, cancelled_at=?, cancelled_by=?, cancel_reason=?, attended_at=?, checked_in_by=? WHERE registration_id=?",
                        r.status(), r.getCancelledAt(), r.getCancelledBy(), r.getCancelReason(), r.getAttendedAt(), r.getCheckedInBy(), r.getId());
            }
        } catch (RuntimeException e) {
            if (db.isUniqueViolation(e)) throw new AlreadyRegisteredException("error.registration.duplicate");
            throw e;
        }
        return r;
    }

    @Override public Optional<Registration> findById(long id) { return db.one("SELECT " + COLS + " FROM registration WHERE registration_id=?", JdbcRegistrationRepository::map, id); }
    @Override public Optional<Registration> findActive(long eventId, long userId) {
        return db.one("SELECT " + COLS + " FROM registration WHERE event_id=? AND user_id=? AND status IN ('CONFIRMED','WAITLISTED','ATTENDED')", JdbcRegistrationRepository::map, eventId, userId);
    }
    @Override public int countSeatsHeld(long eventId) { return (int) db.count("SELECT count(*) FROM registration WHERE event_id=? AND status IN ('CONFIRMED','ATTENDED')", eventId); }
    @Override public int countWaitlisted(long eventId) { return (int) db.count("SELECT count(*) FROM registration WHERE event_id=? AND status='WAITLISTED'", eventId); }
    @Override public List<Registration> findWaitlist(long eventId) {
        return db.list("SELECT " + COLS + " FROM registration WHERE event_id=? AND status='WAITLISTED' ORDER BY registered_at, registration_id", JdbcRegistrationRepository::map, eventId);
    }

    @Override public List<RegistrantRow> findRegistrants(long eventId, String search) {
        StringBuilder sql = new StringBuilder("SELECT r.registration_id, r.event_id, r.user_id, r.status, r.registered_at, r.cancelled_at, r.cancelled_by, r.cancel_reason, "
                + "r.attended_at, r.checked_in_by, u.university_id, u.full_name_en, u.full_name_ar FROM registration r JOIN app_user u ON u.user_id=r.user_id WHERE r.event_id=?");
        List<Object> args = new ArrayList<>(List.of(eventId));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (u.university_id").append(db.dialect().ilike()).append("? OR u.full_name_en").append(db.dialect().ilike())
               .append("? OR COALESCE(u.full_name_ar,'')").append(db.dialect().ilike()).append("?)");
            args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY r.registered_at, r.registration_id");
        List<RegistrantRow> rows = new ArrayList<>();
        int pos = 0;
        // waitlist positions are relative to all waitlisted rows, so compute them from an unfiltered pass
        Map<Long, Integer> positions = new HashMap<>();
        for (Registration w : findWaitlist(eventId)) positions.put(w.getId(), ++pos);
        for (RegistrantRow row : db.list(sql.toString(), rs -> {
            Registration r = map(rs);
            return new RegistrantRow(r, rs.getString("university_id"), new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")), 0);
        }, args.toArray()))
            rows.add(new RegistrantRow(row.registration(), row.universityId(), row.name(), positions.getOrDefault(row.registration().getId(), 0)));
        return rows;
    }

    @Override public List<MyRegistration> findByUser(long userId) {
        List<Registration> regs = db.list("SELECT " + COLS + " FROM registration WHERE user_id=? ORDER BY registered_at DESC", JdbcRegistrationRepository::map, userId);
        Set<Long> ids = new HashSet<>();
        for (Registration r : regs) ids.add(r.getEventId());
        Map<Long, Event> byId = new HashMap<>();
        for (Event e : events.findByIds(ids)) byId.put(e.getId(), e);
        List<MyRegistration> out = new ArrayList<>();
        for (Registration r : regs) {
            Event e = byId.get(r.getEventId());
            if (e == null) continue;
            out.add(new MyRegistration(r, e, r.status() == RegistrationStatus.WAITLISTED ? waitlistPosition(r.getId()) : 0));
        }
        return out;
    }

    @Override public int waitlistPosition(long registrationId) {
        return (int) db.count("SELECT count(*) FROM registration r2 JOIN registration r ON r.event_id=r2.event_id WHERE r.registration_id=? AND r2.status='WAITLISTED' "
                + "AND (r2.registered_at<r.registered_at OR (r2.registered_at=r.registered_at AND r2.registration_id<=r.registration_id))", registrationId);
    }

    @Override public Map<Long, int[]> countsFor(Collection<Long> eventIds) {
        Map<Long, int[]> m = new HashMap<>();
        if (eventIds.isEmpty()) return m;
        db.list("SELECT event_id, count(*) FILTER (WHERE status IN ('CONFIRMED','ATTENDED')), count(*) FILTER (WHERE status='WAITLISTED'), "
                + "count(*) FILTER (WHERE status='ATTENDED') FROM registration WHERE event_id = ANY(?) GROUP BY event_id",
                rs -> m.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)}), eventIds);
        for (Long id : eventIds) m.putIfAbsent(id, new int[]{0, 0, 0});
        return m;
    }

    @Override public List<Long> findUserIds(long eventId, RegistrationStatus... statuses) {
        List<String> names = new ArrayList<>();
        for (RegistrationStatus s : statuses) names.add(s.name());
        return db.list("SELECT user_id FROM registration WHERE event_id=? AND status = ANY(?) ORDER BY registered_at", rs -> rs.getLong(1), eventId, names);
    }

    @Override public List<Long> cancelAllActive(long eventId, Instant at, long by, String reason) {
        return db.list("UPDATE registration SET status='CANCELLED', cancelled_at=?, cancelled_by=?, cancel_reason=? WHERE event_id=? AND status IN ('CONFIRMED','WAITLISTED') RETURNING user_id",
                rs -> rs.getLong(1), at, by, reason, eventId);
    }

    @Override public void saveAttendance(long registrationId, long sessionId, boolean present, Instant at, long by) {
        db.exec("INSERT INTO session_attendance (registration_id, session_id, present, marked_at, marked_by) VALUES (?,?,?,?,?) "
                + "ON CONFLICT (registration_id, session_id) DO UPDATE SET present=EXCLUDED.present, marked_at=EXCLUDED.marked_at, marked_by=EXCLUDED.marked_by",
                registrationId, sessionId, present, at, by);
    }
    @Override public void deleteAttendance(long registrationId) { db.exec("DELETE FROM session_attendance WHERE registration_id=?", registrationId); }

    @Override public Map<Long, Set<Long>> findPresentSessions(long eventId) {
        Map<Long, Set<Long>> m = new HashMap<>();
        db.list("SELECT sa.registration_id, sa.session_id FROM session_attendance sa JOIN registration r ON r.registration_id=sa.registration_id WHERE r.event_id=? AND sa.present",
                rs -> m.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2)), eventId);
        return m;
    }

    @Override public Set<Long> findAttendedCategoryIds(long userId) {
        return new HashSet<>(db.list("SELECT DISTINCT e.category_id FROM registration r JOIN event e ON e.event_id=r.event_id WHERE r.user_id=? AND r.status='ATTENDED' AND e.category_id IS NOT NULL",
                rs -> rs.getLong(1), userId));
    }
}

// ========== SERVICES ==========

/**
 * Observer that turns every {@link DomainEvent} into stored notifications (SUC-6) and offers the notification
 * center operations (FR-NTF-01..06).
 */
class NotificationService implements DomainEventListener {
    private final TransactionManager tx;
    private final NotificationRepository repo;
    private final SessionManager sessions;
    private final Clock clock;

    public NotificationService(TransactionManager tx, NotificationRepository repo, SessionManager sessions, Clock clock) {
        this.tx = tx; this.repo = repo; this.sessions = sessions; this.clock = clock;
    }

    /** Stores the notifications of the event in the publisher's transaction. */
    @Override public void handle(DomainEvent event) {
        for (NotificationSpec s : event.notifications()) repo.save(s, clock.instant());
    }

    /** The caller's notifications, newest first. */
    public List<Notification> list(UserSession s, int limit) {
        sessions.validate(s);
        return tx.inTransaction(() -> repo.findByUser(s.getUserId(), limit));
    }
    /** Unread count for the badge; polled by the client every 30 seconds (FR-NTF-06). */
    public int unreadCount(UserSession s) {
        sessions.validate(s);
        return tx.inTransaction(() -> repo.countUnread(s.getUserId()));
    }
    public void markRead(UserSession s, long id) { sessions.validate(s); tx.runInTransaction(() -> repo.markRead(id, s.getUserId())); }
    public void markAllRead(UserSession s) { sessions.validate(s); tx.runInTransaction(() -> repo.markAllRead(s.getUserId())); }
    public void delete(UserSession s, long id) { sessions.validate(s); tx.runInTransaction(() -> repo.delete(id, s.getUserId())); }
}

/** Attendance list of an event with the sessions each registrant attended and totals (FR-CHK-06). */
record AttendanceView(List<RegistrantRow> rows, Map<Long, Set<Long>> presentSessions, int present, int absent, int confirmed) {}

/** Manual check-in of attendees (UC-14, FR-CHK-01..06). */
class CheckInService {
    private static final Duration BEFORE = Duration.ofMinutes(30);
    private static final Duration AFTER = Duration.ofHours(24);
    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public CheckInService(TransactionManager tx, EventRepository events, RegistrationRepository regs, AuthorizationService authz, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.authz = authz; this.publisher = publisher; this.clock = clock;
    }

    /** The attendance list: Confirmed and Attended registrants, searchable by name or ID (FR-CHK-06). */
    public AttendanceView attendanceList(UserSession s, long eventId, String search) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            authz.requireOrganizer(s, events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound")));
            List<RegistrantRow> rows = new ArrayList<>();
            for (RegistrantRow r : regs.findRegistrants(eventId, search))
                if (r.registration().status() == RegistrationStatus.CONFIRMED || r.registration().status() == RegistrationStatus.ATTENDED) rows.add(r);
            int present = 0;
            for (RegistrantRow r : rows) if (r.registration().status() == RegistrationStatus.ATTENDED) present++;
            return new AttendanceView(rows, regs.findPresentSessions(eventId), present, rows.size() - present, rows.size());
        });
    }

    /** Marks present. {@code sessionId} is required for workshops (FR-CHK-04) and ignored otherwise. */
    public void markPresent(UserSession s, long registrationId, Long sessionId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Registration probe = regs.findById(registrationId).orElseThrow(() -> new NotFoundException("error.registration.notFound"));
            Event event = events.lockById(probe.getEventId());
            authz.requireOrganizer(s, event);
            Registration r = regs.findById(registrationId).orElseThrow();
            Instant now = clock.instant();
            TimeSlot window = windowFor(event, sessionId);
            if (now.isBefore(window.start().minus(BEFORE)) || now.isAfter(window.end().plus(AFTER))) throw new ValidationException("error.checkin.window");
            if (r.status() != RegistrationStatus.CONFIRMED && r.status() != RegistrationStatus.ATTENDED) throw new ConflictException("error.checkin.notConfirmed");
            if (event instanceof Workshop) regs.saveAttendance(registrationId, sessionId, true, now, s.getUserId());
            if (r.status() == RegistrationStatus.CONFIRMED) {
                r.markAttended(now, s.getUserId());
                regs.save(r);
                if (event.status() == EventStatus.COMPLETED) publisher.publish(new FeedbackRequested(event.getId(), event.getTitle(), List.of(r.getUserId())));
            }
        });
    }

    /** Undoes a check-in; the change is logged (FR-CHK-05). */
    public void markAbsent(UserSession s, long registrationId, Long sessionId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Registration probe = regs.findById(registrationId).orElseThrow(() -> new NotFoundException("error.registration.notFound"));
            Event event = events.lockById(probe.getEventId());
            authz.requireOrganizer(s, event);
            Registration r = regs.findById(registrationId).orElseThrow();
            Instant now = clock.instant();
            TimeSlot window = windowFor(event, sessionId);
            if (now.isBefore(window.start().minus(BEFORE)) || now.isAfter(window.end().plus(AFTER))) throw new ValidationException("error.checkin.window");
            if (event instanceof Workshop) {
                regs.saveAttendance(registrationId, sessionId, false, now, s.getUserId());
                boolean any = regs.findPresentSessions(event.getId()).getOrDefault(registrationId, Set.of()).size() > 0;
                if (!any && r.status() == RegistrationStatus.ATTENDED) { r.undoAttendance(); regs.save(r); }
            } else if (r.status() == RegistrationStatus.ATTENDED) { r.undoAttendance(); regs.save(r); }
            publisher.publish(new CheckInChanged(s.getUserId(), registrationId, "CHECKIN_UNDO"));
        });
    }

    private TimeSlot windowFor(Event event, Long sessionId) {
        if (event instanceof Workshop w) {
            if (sessionId == null) throw new ValidationException("error.checkin.sessionRequired");
            return w.getSessions().stream().filter(x -> x.getId() == sessionId).findFirst()
                    .orElseThrow(() -> new NotFoundException("error.session.notFound")).getSlot();
        }
        if (event.getStartAt() == null) throw new ValidationException("error.checkin.window");
        return new TimeSlot(event.getStartAt(), event.getEndAt());
    }
}

/** Default clash strategy: compares time intervals; back-to-back slots are not clashes (SUC-1, BR-07). */
class IntervalClashStrategy implements ClashDetectionStrategy {
    private final EventRepository events;
    public IntervalClashStrategy(EventRepository events) { this.events = events; }

    @Override public Optional<Clash> findClash(long studentId, long targetEventId, List<TimeSlot> target) {
        Instant earliest = target.stream().map(TimeSlot::start).min(Comparator.naturalOrder()).orElseThrow();
        for (Event other : events.findActiveRegisteredBy(studentId, earliest)) {
            if (other.getId() == targetEventId) continue;
            Optional<TimeSlot[]> hit = TimeSlot.firstOverlap(target, other.getTimeSlots());
            if (hit.isPresent()) return Optional.of(new Clash(other.getId(), other.getTitle(), hit.get()[1]));
        }
        return Optional.empty();
    }
}

/**
 * Facade (single entry point) for register, cancel and promote (UC-05, UC-06). Hides validation, clash detection,
 * locking, waitlist handling, persistence and event publishing, so every registration change is one transaction
 * (FR-REG-05, NFR-REL-01).
 */
class RegistrationFacade {
    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final UserRepository users;
    private final ClashDetectionStrategy clash;
    private final WaitlistService waitlist;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public RegistrationFacade(TransactionManager tx, EventRepository events, RegistrationRepository regs, UserRepository users,
                              ClashDetectionStrategy clash, WaitlistService waitlist, AuthorizationService authz,
                              DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.users = users; this.clash = clash;
        this.waitlist = waitlist; this.authz = authz; this.publisher = publisher; this.clock = clock;
    }

    /**
     * Registers the caller for an Open event. Locks the event row, so concurrent requests can never produce more
     * Confirmed registrations than the capacity (BR-04). Returns the new registration (Confirmed or Waitlisted).
     */
    public Registration register(UserSession s, long eventId) {
        authz.require(s, Permission.REGISTER);
        return tx.inTransaction(() -> {
            Event event = events.lockById(eventId);                       // 1. serialize per event
            if (event.status() != EventStatus.OPEN) throw new NotOpenException("error.registration.notOpen");
            if (!clock.instant().isBefore(event.effectiveRegDeadline())) throw new NotOpenException("error.registration.notOpen");
            users.lockById(s.getUserId());                                 // 2. serialize per student (clash checks)
            if (regs.findActive(eventId, s.getUserId()).isPresent()) throw new AlreadyRegisteredException("error.registration.duplicate");
            Optional<Clash> c = clash.findClash(s.getUserId(), eventId, event.getTimeSlots());
            if (c.isPresent()) throw new ScheduleClashException(c.get().eventTitle(), c.get().slot());
            Instant now = clock.instant();
            if (regs.countSeatsHeld(eventId) < event.getCapacity()) {
                Registration r = regs.save(Registration.confirmed(eventId, s.getUserId(), now));
                publisher.publish(new RegistrationConfirmed(s.getUserId(), eventId, event.getTitle()));
                return r;
            }
            Registration r = regs.save(Registration.waitlisted(eventId, s.getUserId(), now));
            publisher.publish(new RegistrationWaitlisted(s.getUserId(), eventId, event.getTitle(), regs.waitlistPosition(r.getId())));
            return r;
        });
    }

    /** Cancels the caller's own registration until the cancellation deadline and promotes from the waitlist (UC-06). */
    public void cancel(UserSession s, long registrationId) {
        authz.require(s, Permission.REGISTER);
        tx.runInTransaction(() -> doCancel(registrationId, s.getUserId(), null, null, true));
    }

    /** An organizer cancels a student's registration at any time with a logged reason (FR-REG-11). */
    public void cancelOnBehalf(UserSession s, long registrationId, String reason) {
        authz.requireSession(s);
        if (reason == null || reason.isBlank()) throw new ValidationException("error.reason.required");
        tx.runInTransaction(() -> {
            Registration r = regs.findById(registrationId).orElseThrow(() -> new NotFoundException("error.registration.notFound"));
            authz.requireOrganizer(s, events.findById(r.getEventId()).orElseThrow(() -> new NotFoundException("error.event.notFound")));
            doCancel(registrationId, null, s.getUserId(), reason.trim(), false);
        });
    }

    private void doCancel(long registrationId, Long ownerId, Long organizerId, String reason, boolean enforceDeadline) {
        Registration probe = regs.findById(registrationId).orElseThrow(() -> new NotFoundException("error.registration.notFound"));
        Event event = events.lockById(probe.getEventId());
        Registration r = regs.findById(registrationId).orElseThrow(() -> new NotFoundException("error.registration.notFound"));
        if (ownerId != null && r.getUserId() != ownerId) throw new AuthorizationException("error.forbidden");
        Instant now = clock.instant();
        EventStatus st = event.status();
        if (st == EventStatus.CANCELLED || st == EventStatus.COMPLETED || st == EventStatus.ONGOING)
            throw new CancellationClosedException("error.registration.cancelClosed", event.effectiveCancelDeadline());
        if (enforceDeadline && now.isAfter(event.effectiveCancelDeadline()))
            throw new CancellationClosedException("error.registration.cancelClosed", event.effectiveCancelDeadline());
        boolean heldSeat = r.status() == RegistrationStatus.CONFIRMED;
        r.cancel(now, organizerId != null ? organizerId : ownerId, reason);
        regs.save(r);
        publisher.publish(new RegistrationCancelled(r.getUserId(), event.getId(), event.getTitle(), organizerId, reason));
        if (heldSeat) waitlist.promote(event);
    }

    /** Previews whether registering would clash (shown as a warning on the event page, UC-05 step 5). */
    public Optional<Clash> previewClash(UserSession s, long eventId) {
        authz.require(s, Permission.REGISTER);
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            return clash.findClash(s.getUserId(), eventId, e.getTimeSlots());
        });
    }

    /** Seat figures and the caller's own status for the event. Any logged-in user may call it. */
    public SeatInfo seatInfo(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            Optional<Registration> mine = regs.findActive(eventId, s.getUserId());
            int pos = mine.filter(r -> r.status() == RegistrationStatus.WAITLISTED).map(r -> regs.waitlistPosition(r.getId())).orElse(0);
            return new SeatInfo(e.getCapacity(), regs.countSeatsHeld(eventId), regs.countWaitlisted(eventId),
                    mine.map(Registration::status).orElse(null), pos, mine.map(Registration::getId).orElse(0L));
        });
    }

    /** The caller's registrations with status and waitlist position (FR-REG-08). */
    public List<MyRegistration> findMine(UserSession s) {
        authz.require(s, Permission.REGISTER);
        return tx.inTransaction(() -> regs.findByUser(s.getUserId()));
    }

    /** Registrants of an event for organizers: name, ID, status, time (FR-REG-11, FR-WTL-06). */
    public List<RegistrantRow> findRegistrants(UserSession s, long eventId, String search) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            authz.requireOrganizer(s, events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound")));
            return regs.findRegistrants(eventId, search);
        });
    }
}

/** Seat figures and the caller's own registration status for the event page (FR-EVT-08). */
record SeatInfo(int capacity, int confirmed, int waitlisted, RegistrationStatus myStatus, int myPosition, long myRegistrationId) {
    public int seatsLeft() { return Math.max(0, capacity - confirmed); }
}

/** Promotes waitlisted students first-in-first-out when seats become free (SUC-3, FR-WTL-01..05). Must run inside the caller's transaction. */
class WaitlistService {
    private final RegistrationRepository regs;
    private final UserRepository users;
    private final ClashDetectionStrategy clash;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public WaitlistService(RegistrationRepository regs, UserRepository users, ClashDetectionStrategy clash, DomainEventPublisher publisher, Clock clock) {
        this.regs = regs; this.users = users; this.clash = clash; this.publisher = publisher; this.clock = clock;
    }

    /**
     * Fills every free seat from the waitlist. The caller holds the event lock. A student with a clash is skipped,
     * stays on the waitlist and is told (NT-04); the next student is considered. Returns the number promoted.
     */
    public int promote(Event event) {
        Instant now = clock.instant();
        boolean eligible = (event.status() == EventStatus.OPEN || event.status() == EventStatus.CLOSED) && !now.isAfter(event.effectiveCancelDeadline());
        if (!eligible) return 0;
        int free = event.getCapacity() - regs.countSeatsHeld(event.getId());
        int promoted = 0;
        List<Registration> waitlist = regs.findWaitlist(event.getId());
        for (Registration w : waitlist) {
            if (free <= 0) break;
            users.lockById(w.getUserId());   // serialize with concurrent registrations by the same student
            Optional<Clash> c = clash.findClash(w.getUserId(), event.getId(), event.getTimeSlots());
            if (c.isPresent()) {
                publisher.publish(new PromotionSkipped(w.getUserId(), event.getId(), event.getTitle()));
                continue;
            }
            w.promote();
            regs.save(w);
            publisher.publish(new WaitlistPromoted(w.getUserId(), event.getId(), event.getTitle()));
            free--; promoted++;
        }
        return promoted;
    }
}

/** Writes RFC 5545 iCalendar text: CRLF line ends, escaping and 75-octet line folding (FR-CAL-02). */
final class IcsBuilder {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final StringBuilder sb = new StringBuilder();

    public IcsBuilder() {
        line("BEGIN:VCALENDAR"); line("VERSION:2.0"); line("PRODID:-//UEMS//University Event Management System//EN"); line("CALSCALE:GREGORIAN");
    }

    /** Adds one VEVENT. The UID is stable so re-importing updates the entry (FR-CAL-03). */
    public IcsBuilder event(String uid, Instant stamp, Instant start, Instant end, String summary, String location, String description) {
        line("BEGIN:VEVENT");
        line("UID:" + uid);
        line("DTSTAMP:" + UTC.format(stamp));
        line("DTSTART:" + UTC.format(start));
        line("DTEND:" + UTC.format(end));
        line("SUMMARY:" + escape(summary));
        if (location != null && !location.isBlank()) line("LOCATION:" + escape(location));
        if (description != null && !description.isBlank()) line("DESCRIPTION:" + escape(description));
        line("END:VEVENT");
        return this;
    }

    public String build() { return sb + "END:VCALENDAR\r\n"; }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Appends a content line, folding at 75 octets without splitting a UTF-8 character. */
    private void line(String text) {
        StringBuilder cur = new StringBuilder();
        int bytes = 0, limit = 75;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int n = ch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > limit) { sb.append(cur).append("\r\n"); cur.setLength(0); cur.append(' '); bytes = 1; limit = 75; }
            cur.append(ch); bytes += n; i += Character.charCount(cp);
        }
        sb.append(cur).append("\r\n");
    }
}

/** Exports events to iCalendar text (.ics); the UI writes the file the user chose (SUC-7, FR-CAL-02..04). */
class CalendarExportService {
    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final VenueRepository venues;
    private final AuthorizationService authz;
    private final Clock clock;

    public CalendarExportService(TransactionManager tx, EventRepository events, RegistrationRepository regs, VenueRepository venues, AuthorizationService authz, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.venues = venues; this.authz = authz; this.clock = clock;
    }

    /** Exports the selected events (workshops: one entry per session). */
    public String exportEvents(UserSession s, Collection<Long> eventIds) {
        authz.require(s, Permission.CALENDAR);
        return tx.inTransaction(() -> {
            List<Event> list = events.findByIds(eventIds);
            if (list.isEmpty()) throw new NotFoundException("error.event.notFound");
            return build(s.getLanguage(), list);
        });
    }

    /** Exports all of the caller's Confirmed registrations (FR-CAL-02). */
    public String exportMine(UserSession s) {
        authz.require(s, Permission.CALENDAR);
        return tx.inTransaction(() -> {
            List<Event> list = new ArrayList<>();
            for (MyRegistration r : regs.findByUser(s.getUserId())) if (r.registration().status() == RegistrationStatus.CONFIRMED && r.event().status().isPublic()) list.add(r.event());
            return build(s.getLanguage(), list);
        });
    }

    private String build(Language lang, List<Event> list) {
        IcsBuilder ics = new IcsBuilder();
        Map<Long, String> venueNames = new HashMap<>();
        for (Event e : list) {
            String loc = venueName(venueNames, e.getVenueId(), lang);
            String desc = e.getDescription().get(lang);
            if (e instanceof Workshop w) {
                for (WorkshopSession x : w.getSessions()) {
                    String title = e.getTitle().get(lang) + " - " + x.getTopic().get(lang);
                    ics.event("uems-event-" + e.getId() + "-s" + x.getId() + "@uems.local", clock.instant(), x.getSlot().start(), x.getSlot().end(), title,
                            x.getVenueId() != null ? venueName(venueNames, x.getVenueId(), lang) : loc, desc);
                }
            } else ics.event("uems-event-" + e.getId() + "@uems.local", clock.instant(), e.getStartAt(), e.getEndAt(), e.getTitle().get(lang), loc, desc);
        }
        return ics.build();
    }

    private String venueName(Map<Long, String> cache, Long id, Language lang) {
        if (id == null) return "";
        return cache.computeIfAbsent(id, k -> venues.findById(k).map(Venue::getName).map(n -> n.get(lang)).orElse(""));
    }
}
