package uems;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// MODULE 1 SERVICES: authentication, authorization, sessions, users, venues, notifications, audit log, settings.

// ======== service.auth ========

/** Login, logout, lockout and password change (UC-01, FR-AUT-01..10). */
class AuthService {
    public static final int MAX_FAILED = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(15);
    private final TransactionManager tx;
    private final UserRepository users;
    private final CatalogRepository catalog;
    private final SessionManager sessions;
    private final PasswordHasher hasher;
    private final DomainEventPublisher publisher;
    private final Clock clock;
    private final PasswordHash dummy;

    public AuthService(TransactionManager tx, UserRepository users, CatalogRepository catalog, SessionManager sessions,
                       PasswordHasher hasher, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.users = users; this.catalog = catalog; this.sessions = sessions; this.hasher = hasher; this.publisher = publisher; this.clock = clock;
        this.dummy = hasher.hash("dummy-password-1".toCharArray());
    }

    private record Outcome(UserSession session, RuntimeException failure) {}

    /** Authenticates. Failures are recorded (and committed) before the exception is thrown. The password array is wiped. */
    public UserSession login(String universityId, char[] password) {
        String uid = universityId == null ? "" : universityId.trim();
        Outcome out = tx.inTransaction(() -> attempt(uid, password));
        if (out.failure() != null) throw out.failure();
        return out.session();
    }

    private Outcome attempt(String uid, char[] password) {
        Optional<User> ou = users.lockByUniversityId(uid);
        if (ou.isEmpty()) {
            hasher.verify(password, dummy);   // uniform timing, no hint whether the ID exists
            publisher.publish(new LoginFailed(uid, 0));
            return new Outcome(null, new InvalidCredentialsException("error.login.invalid"));
        }
        User u = ou.get();
        Instant now = clock.instant();
        if (u.getStatus() != UserStatus.ACTIVE) {
            hasher.verify(password, dummy);
            publisher.publish(new LoginFailed(uid, 0));
            return new Outcome(null, new InvalidCredentialsException("error.login.disabled"));
        }
        if (u.isLocked(now)) {
            hasher.verify(password, dummy);
            return new Outcome(null, new AccountLockedException(u.getLockedUntil()));
        }
        if (hasher.verify(password, u.getPasswordHash())) {
            u.setFailedAttempts(0); u.setLockedUntil(null);
            users.save(u);
            sessions.setTimeout(Duration.ofMinutes(catalog.getSetting("session.timeout.minutes").map(Integer::parseInt).orElse(20)));
            publisher.publish(new UserLoggedIn(u.getId(), uid));
            return new Outcome(sessions.create(u), null);
        }
        int attempts = u.getFailedAttempts() + 1;
        publisher.publish(new LoginFailed(uid, attempts));
        if (attempts >= MAX_FAILED) {
            Instant until = now.plus(LOCK_TIME);
            u.setFailedAttempts(0); u.setLockedUntil(until);
            users.save(u);
            publisher.publish(new AccountLocked(uid, until));
            return new Outcome(null, new AccountLockedException(until));
        }
        u.setFailedAttempts(attempts);
        users.save(u);
        return new Outcome(null, new InvalidCredentialsException("error.login.invalid"));
    }

    /** Ends the session; the token cannot be reused (FR-AUT-04). */
    public void logout(UserSession s) { sessions.end(s); }

    /** Changes the caller's password after verifying the old one (FR-AUT-07, FR-AUT-08). Both arrays are wiped. */
    public void changePassword(UserSession s, char[] oldPw, char[] newPw) {
        sessions.validate(s);
        try {
            PasswordPolicy.check(newPw);
            tx.runInTransaction(() -> {
                User u = users.lockById(s.getUserId()).orElseThrow(() -> new NotFoundException("error.user.notFound"));
                if (!hasher.verify(oldPw, u.getPasswordHash())) throw new InvalidCredentialsException("error.password.wrongOld");
                u.setPasswordHash(hasher.hash(newPw.clone()));
                u.setMustChangePassword(false);
                users.save(u);
                publisher.publish(new AccountChanged(u.getId(), u.getId(), "PASSWORD_CHANGE", null));
            });
            s.setMustChangePassword(false);
        } finally { java.util.Arrays.fill(newPw, '\0'); java.util.Arrays.fill(oldPw, '\0'); }
    }
}

/**
 * Central permission check used by every application service, so authorization never depends on the user interface
 * alone (NFR-SEC-03). Base rights come from the account type; officer rights are checked per club (BR-14).
 */
class AuthorizationService {
    private final SessionManager sessions;
    private final ClubRepository clubs;
    private final TransactionManager tx;

    public AuthorizationService(SessionManager sessions, ClubRepository clubs, TransactionManager tx) {
        this.sessions = sessions; this.clubs = clubs; this.tx = tx;
    }

    /** Requires a valid session (touches it) and, unless the person must first change the password, returns. */
    public void requireSession(UserSession s) {
        sessions.validate(s);
        if (s.isMustChangePassword()) throw new AuthorizationException("error.mustChangePassword");
    }

    /** Requires a valid session and a base permission of the account type. */
    public void require(UserSession s, Permission p) {
        requireSession(s);
        if (!s.getAccountType().basePermissions().contains(p)) throw new AuthorizationException("error.forbidden");
    }

    /** Requires the base permission, or, for club-scoped permissions, an officer position in the given club. */
    public void require(UserSession s, Permission p, Long clubId) {
        requireSession(s);
        if (s.getAccountType().basePermissions().contains(p)) return;
        if (!(p.isClubScoped() && clubId != null && isOfficer(s, clubId))) throw new AuthorizationException("error.forbidden");
    }

    /** True when the permission is held (no exception). Requires an active transaction only for the club check. */
    public boolean can(UserSession s, Permission p, Long clubId) {
        if (!sessions.isActive(s) || s.isMustChangePassword()) return false;
        if (s.getAccountType().basePermissions().contains(p)) return true;
        return p.isClubScoped() && clubId != null && isOfficer(s, clubId);
    }

    /** True when the user holds an active officer position in the club. */
    public boolean isOfficer(UserSession s, long clubId) {
        return tx.inTransaction(() -> clubs.isActiveOfficer(s.getUserId(), clubId));
    }

    /**
     * Requires that the caller organizes the event: Staff (any event) or an officer of the event's host club (BR-14).
     * Must run inside a transaction.
     */
    public void requireOrganizer(UserSession s, Event event) {
        requireSession(s);
        if (s.getAccountType().basePermissions().contains(Permission.MANAGE_EVENTS)) return;
        if (event.getHostClubId() != null && clubs.isActiveOfficer(s.getUserId(), event.getHostClubId())) return;
        throw new AuthorizationException("error.forbidden");
    }
}

/** Password rules (FR-AUT-07): at least 8 characters with a letter and a digit. */
final class PasswordPolicy {
    private PasswordPolicy() {}
    /** Throws ValidationException when the password is too weak. */
    public static void check(char[] pw) {
        boolean letter = false, digit = false;
        for (char c : pw) { if (Character.isLetter(c)) letter = true; if (Character.isDigit(c)) digit = true; }
        if (pw.length < 8 || !letter || !digit) throw new ValidationException("error.password.weak");
    }
}

/** Creates, validates and ends sessions. Sessions are in memory, expire after inactivity and cannot be reused after logout (NFR-SEC-02). */
class SessionManager {
    private record Entry(UserSession session, Instant[] lastActivity) {}
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile Duration timeout;

    public SessionManager(Clock clock, Duration timeout) { this.clock = clock; this.timeout = timeout; }

    /** Changes the inactivity timeout (FR-AUT-03). */
    public void setTimeout(Duration d) { this.timeout = d; }
    public Duration getTimeout() { return timeout; }

    /** Starts a session for the user. */
    public UserSession create(User user) {
        UserSession s = new UserSession(SecureTokens.newToken(), user, clock.instant());
        sessions.put(s.getToken(), new Entry(s, new Instant[]{clock.instant()}));
        return s;
    }

    /** Checks that the session exists and has not timed out, and records activity. Throws SessionExpiredException otherwise. */
    public void validate(UserSession session) {
        Entry e = session == null ? null : sessions.get(session.getToken());
        if (e == null) throw new SessionExpiredException("error.session.expired");
        Instant now = clock.instant();
        synchronized (e) {
            if (Duration.between(e.lastActivity()[0], now).compareTo(timeout) > 0) {
                sessions.remove(session.getToken());
                throw new SessionExpiredException("error.session.expired");
            }
            e.lastActivity()[0] = now;
        }
    }

    /** Ends the session. */
    public void end(UserSession session) { if (session != null) sessions.remove(session.getToken()); }

    /** True when the session is still valid, without touching it. */
    public boolean isActive(UserSession session) {
        Entry e = session == null ? null : sessions.get(session.getToken());
        return e != null && Duration.between(e.lastActivity()[0], clock.instant()).compareTo(timeout) <= 0;
    }
}

// ======== service.user ========

/** Input for creating or editing an account (FR-USR-01). */
class UserData {
    public String universityId = "";
    public LocalizedText fullName = LocalizedText.EMPTY;
    public String email = "";
    public AccountType accountType = AccountType.STUDENT;
    public String faculty = "";
    public Language language = Language.EN;
}

/** User administration (UC-02, FR-USR-01..11) and profile management (FR-USR-05..07). */
class UserService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final TransactionManager tx;
    private final UserRepository users;
    private final ClubRepository clubs;
    private final AuthorizationService authz;
    private final SessionManager sessions;
    private final PasswordHasher hasher;
    private final DomainEventPublisher publisher;
    private final SecureRandom random = new SecureRandom();

    public UserService(TransactionManager tx, UserRepository users, ClubRepository clubs, AuthorizationService authz,
                       SessionManager sessions, PasswordHasher hasher, DomainEventPublisher publisher) {
        this.tx = tx; this.users = users; this.clubs = clubs; this.authz = authz; this.sessions = sessions; this.hasher = hasher; this.publisher = publisher;
    }

    /** Generates a random temporary password that satisfies the policy. */
    String tempPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb + "a7";
    }

    /** Student university number: 9 digits, the registration year (20xx) followed by 5 digits. */
    public static final java.util.regex.Pattern STUDENT_ID = java.util.regex.Pattern.compile("20\\d{2}\\d{5}");

    /**
     * Checks the university ID. Students need the 9-digit format (registration year 20xx + 5 digits);
     * Staff and Administrator IDs are free text of at most 30 characters.
     */
    private static void validateId(AccountType type, String universityId) {
        String id = universityId == null ? "" : universityId.trim();
        if (type == AccountType.STUDENT) {
            if (!STUDENT_ID.matcher(id).matches()) throw new ValidationException("error.user.idFormat");
        } else if (id.isEmpty() || id.length() > 30) throw new ValidationException("error.user.idRequired");
    }

    /** Generates an unused student ID: the current year followed by 5 random digits. Must run inside a transaction. */
    private String generateStudentId() {
        int year = java.time.LocalDate.now(Gst.ZONE).getYear();
        for (int i = 0; i < 100; i++) {
            String id = String.format("%04d%05d", year, random.nextInt(100_000));
            if (users.findByUniversityId(id).isEmpty()) return id;
        }
        throw new ConflictException("error.user.duplicateId");
    }

    private void validate(UserData d) {
        if (d.fullName.isBlank()) throw new ValidationException("error.user.nameRequired");
        if (d.email == null || !d.email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new ValidationException("error.user.emailInvalid");
    }

    /**
     * Creates an account with a temporary password that must be changed at first login; returns the password once.
     * For a student whose university ID is left empty, an ID is generated (current year + 5 random digits) and
     * written back into {@code d.universityId} so the caller can show it.
     */
    public String create(UserSession s, UserData d) {
        authz.require(s, Permission.MANAGE_USERS);
        validate(d);
        String temp = tempPassword();
        tx.runInTransaction(() -> {
            if (d.accountType == AccountType.STUDENT && (d.universityId == null || d.universityId.isBlank())) d.universityId = generateStudentId();
            validateId(d.accountType, d.universityId);
            if (users.findByUniversityId(d.universityId.trim()).isPresent()) throw new ConflictException("error.user.duplicateId");
            User u = build(d, temp, true);
            users.save(u);
            publisher.publish(new AccountChanged(s.getUserId(), u.getId(), "USER_CREATE", d.accountType.name()));
        });
        return temp;
    }

    private User build(UserData d, String password, boolean mustChange) {
        User u = User.create(d.accountType);
        u.setUniversityId(d.universityId.trim()); u.setFullName(d.fullName); u.setEmail(d.email.trim());
        u.setFaculty(d.faculty); u.setLanguage(d.language);
        u.setPasswordHash(hasher.hash(password.toCharArray())); u.setMustChangePassword(mustChange);
        return u;
    }

    /** Edits name, e-mail, faculty and, with BR-01/last-admin checks, the account type. */
    public void update(UserSession s, long userId, UserData d) {
        authz.require(s, Permission.MANAGE_USERS);
        validate(d);
        tx.runInTransaction(() -> {
            User old = users.lockById(userId).orElseThrow(() -> new NotFoundException("error.user.notFound"));
            if (old.getStatus() == UserStatus.ANONYMIZED) throw new ConflictException("error.user.anonymized");
            Optional<User> dup = users.findByUniversityId(d.universityId.trim());
            if (dup.isPresent() && dup.get().getId() != userId) throw new ConflictException("error.user.duplicateId");
            AccountType from = old.accountType();
            if (!old.getUniversityId().equals(d.universityId.trim()) || from != d.accountType) validateId(d.accountType, d.universityId);
            User u = old;
            if (from != d.accountType) {
                if (d.accountType == AccountType.STAFF && clubs.holdsOfficerPosition(userId)) throw new ConflictException("error.user.staffOfficer");
                if (from == AccountType.ADMIN && old.getStatus() == UserStatus.ACTIVE && users.countActiveAdmins() <= 1) throw new ConflictException("error.user.lastAdmin");
                u = User.create(d.accountType);
                u.setId(old.getId()); u.setPasswordHash(old.getPasswordHash()); u.setMustChangePassword(old.isMustChangePassword());
                u.setStatus(old.getStatus()); u.setFailedAttempts(old.getFailedAttempts()); u.setLockedUntil(old.getLockedUntil());
            }
            u.setUniversityId(d.universityId.trim()); u.setFullName(d.fullName); u.setEmail(d.email.trim()); u.setFaculty(d.faculty); u.setLanguage(d.language);
            users.save(u);
            publisher.publish(new AccountChanged(s.getUserId(), userId, "USER_EDIT", null));
            if (from != d.accountType) publisher.publish(new AccountTypeChanged(s.getUserId(), userId, from, d.accountType));
        });
    }

    /** Disables or re-enables an account; history is kept (FR-USR-03). The last active Admin cannot be disabled. */
    public void setActive(UserSession s, long userId, boolean active) {
        authz.require(s, Permission.MANAGE_USERS);
        tx.runInTransaction(() -> {
            User u = users.lockById(userId).orElseThrow(() -> new NotFoundException("error.user.notFound"));
            if (u.getStatus() == UserStatus.ANONYMIZED) throw new ConflictException("error.user.anonymized");
            if (!active && u.accountType() == AccountType.ADMIN && users.countActiveAdmins() <= 1) throw new ConflictException("error.user.lastAdmin");
            u.setStatus(active ? UserStatus.ACTIVE : UserStatus.DISABLED);
            u.setFailedAttempts(0); u.setLockedUntil(null);
            users.save(u);
            publisher.publish(new AccountChanged(s.getUserId(), userId, active ? "USER_ENABLE" : "USER_DISABLE", null));
        });
    }

    /** Resets the password to a new temporary one that must be changed at next login (FR-USR-04); returns it once. */
    public String resetPassword(UserSession s, long userId) {
        authz.require(s, Permission.MANAGE_USERS);
        String temp = tempPassword();
        tx.runInTransaction(() -> {
            User u = users.lockById(userId).orElseThrow(() -> new NotFoundException("error.user.notFound"));
            u.setPasswordHash(hasher.hash(temp.toCharArray())); u.setMustChangePassword(true);
            u.setFailedAttempts(0); u.setLockedUntil(null);
            users.save(u);
            publisher.publish(new AccountChanged(s.getUserId(), userId, "PASSWORD_RESET", null));
        });
        return temp;
    }

    /** Erasure by anonymization (FR-USR-10, NFR-PRV-04): personal data is replaced, registrations stay for statistics. */
    public void anonymize(UserSession s, long userId) {
        authz.require(s, Permission.MANAGE_USERS);
        tx.runInTransaction(() -> {
            User u = users.lockById(userId).orElseThrow(() -> new NotFoundException("error.user.notFound"));
            if (u.accountType() == AccountType.ADMIN && u.getStatus() == UserStatus.ACTIVE && users.countActiveAdmins() <= 1) throw new ConflictException("error.user.lastAdmin");
            String anon = "ANON-" + u.getId();
            u.setUniversityId(anon); u.setFullName(new LocalizedText("Anonymized user", "مستخدم مجهول"));
            u.setEmail(anon + "@invalid.example"); u.setFaculty("");
            u.setPasswordHash(hasher.hash((tempPassword()).toCharArray())); u.setStatus(UserStatus.ANONYMIZED);
            users.save(u);
            users.saveInterests(userId, Set.of());
            publisher.publish(new AccountChanged(s.getUserId(), userId, "USER_ANONYMIZE", null));
        });
    }

    /** Searches accounts (Admin). */
    public List<User> search(UserSession s, String text, AccountType type, UserStatus status) {
        authz.require(s, Permission.MANAGE_USERS);
        return tx.inTransaction(() -> users.search(text, type, status, 500));
    }

    /**
     * Imports users from CSV text, one per line: universityId,nameEn,nameAr,email,type,faculty (FR-USR-11, Low); a student's ID may be left empty to be generated.
     * Valid lines are created with temporary passwords; returns the number created. Bad lines abort the whole import.
     */
    public int importCsv(UserSession s, String csv) {
        authz.require(s, Permission.MANAGE_USERS);
        List<UserData> rows = new ArrayList<>();
        int line = 0;
        for (String raw : csv.split("\\R")) {
            line++;
            if (raw.isBlank() || raw.startsWith("#")) continue;
            String[] p = raw.split(",", -1);
            if (p.length < 5) throw new ValidationException("error.import.line", line);
            UserData d = new UserData();
            d.universityId = p[0].trim(); d.fullName = new LocalizedText(p[1], p[2]); d.email = p[3].trim();
            try { d.accountType = AccountType.valueOf(p[4].trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new ValidationException("error.import.line", line); }
            d.faculty = p.length > 5 ? p[5].trim() : "";
            try {
                validate(d);
                if (!(d.accountType == AccountType.STUDENT && d.universityId.isBlank())) validateId(d.accountType, d.universityId);   // blank student IDs are generated
            } catch (ValidationException e) { throw new ValidationException("error.import.line", line); }
            rows.add(d);
        }
        return tx.inTransaction(() -> {
            for (UserData d : rows) {
                if (d.universityId.isBlank()) d.universityId = generateStudentId();
                if (users.findByUniversityId(d.universityId).isPresent()) throw new ConflictException("error.user.duplicateId");
                User u = build(d, tempPassword(), true);
                users.save(u);
                publisher.publish(new AccountChanged(s.getUserId(), u.getId(), "USER_CREATE", "csv"));
            }
            return rows.size();
        });
    }

    // ---- profile ----
    /** The caller's own account (FR-USR-05, NFR-PRV-03). */
    public User getProfile(UserSession s) {
        sessions.validate(s);
        return tx.inTransaction(() -> users.findById(s.getUserId()).orElseThrow(() -> new NotFoundException("error.user.notFound")));
    }
    public Set<Long> getInterests(UserSession s) {
        sessions.validate(s);
        return tx.inTransaction(() -> users.findInterests(s.getUserId()));
    }
    /** Updates the caller's e-mail, faculty, language and interests (FR-USR-06, FR-USR-07). Works while a password change is pending. */
    public void updateProfile(UserSession s, String email, String faculty, Language language, Set<Long> interests) {
        sessions.validate(s);
        if (email == null || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new ValidationException("error.user.emailInvalid");
        tx.runInTransaction(() -> {
            User u = users.lockById(s.getUserId()).orElseThrow(() -> new NotFoundException("error.user.notFound"));
            u.setEmail(email.trim()); u.setFaculty(faculty); u.setLanguage(language);
            users.save(u);
            users.saveInterests(u.getId(), interests == null ? Set.of() : interests);
        });
        s.setLanguage(language);
    }
    /** Stores only the language preference (used by the login screen toggle). */
    public void setLanguage(UserSession s, Language language) {
        sessions.validate(s);
        tx.runInTransaction(() -> users.lockById(s.getUserId()).ifPresent(u -> { u.setLanguage(language); users.save(u); }));
        s.setLanguage(language);
    }
    /** Resolves display names for ids (used by organizer screens). */
    public Map<Long, User> findByIds(UserSession s, Collection<Long> ids) {
        authz.requireSession(s);
        return tx.inTransaction(() -> users.findByIds(ids));
    }

    /** Staff look up an account by university ID when appointing club officers (FR-CLB-02). */
    public User lookupForClub(UserSession s, String universityId) {
        authz.require(s, Permission.MANAGE_CLUBS);
        return tx.inTransaction(() -> users.findByUniversityId(universityId == null ? "" : universityId.trim())
                .orElseThrow(() -> new NotFoundException("error.user.notFound")));
    }
}

// ======== service.venue ========

/** Venue administration, availability checks and reservation (UC-09, SUC-4, FR-VEN-01..06). */
class VenueService {
    private final TransactionManager tx;
    private final VenueRepository venues;
    private final EventRepository events;
    private final AuthorizationService authz;
    private final Clock clock;

    public VenueService(TransactionManager tx, VenueRepository venues, EventRepository events, AuthorizationService authz, Clock clock) {
        this.tx = tx; this.venues = venues; this.events = events; this.authz = authz; this.clock = clock;
    }

    /** Lists venues; any logged-in user may see active venues (needed by the event editor and filters). */
    public List<Venue> list(UserSession s, boolean activeOnly) {
        authz.requireSession(s);
        return tx.inTransaction(() -> venues.findAll(activeOnly));
    }

    /** Creates or updates a venue (Admin). */
    public Venue save(UserSession s, Venue v) {
        authz.require(s, Permission.MANAGE_VENUES);
        if (v.getName().isBlank()) throw new ValidationException("error.venue.nameRequired");
        if (v.getCapacity() < 1) throw new ValidationException("error.venue.capacity");
        return tx.inTransaction(() -> {
            if (v.getId() != 0) {
                Venue old = venues.lockById(v.getId()).orElseThrow(() -> new NotFoundException("error.venue.notFound"));
                if (v.getCapacity() < old.getCapacity() && hasFutureBookings(old.getId()))
                    throw new ConflictException("error.venue.hasBookings");
            }
            return venues.save(v);
        });
    }

    /** Deactivates a venue unless it has future bookings (FR-VEN-02). */
    public void deactivate(UserSession s, long venueId) {
        authz.require(s, Permission.MANAGE_VENUES);
        tx.runInTransaction(() -> {
            Venue v = venues.lockById(venueId).orElseThrow(() -> new NotFoundException("error.venue.notFound"));
            if (hasFutureBookings(venueId)) throw new ConflictException("error.venue.hasBookings");
            v.setActive(false);
            venues.save(v);
        });
    }

    private boolean hasFutureBookings(long venueId) {
        Instant now = clock.instant();
        return !events.findBlocking(venueId, new TimeSlot(now, now.plus(3650, ChronoUnit.DAYS)), null).isEmpty();
    }

    /** The venue timetable in a range (FR-VEN-06). */
    public List<Booking> timetable(UserSession s, long venueId, Instant from, Instant to) {
        authz.requireSession(s);
        return tx.inTransaction(() -> events.findBlocking(venueId, new TimeSlot(from, to), null));
    }

    /** Read-only availability check for a list of slots; returns the conflicting bookings (empty = available). */
    public List<Booking> findConflicts(long venueId, List<TimeSlot> slots, Long ignoreEventId) {
        List<Booking> all = new ArrayList<>();
        for (TimeSlot slot : slots) all.addAll(events.findBlocking(venueId, slot, ignoreEventId));
        return all;
    }

    /** UI helper: availability check outside any other operation. */
    public List<Booking> checkAvailability(UserSession s, long venueId, List<TimeSlot> slots, Long ignoreEventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> findConflicts(venueId, slots, ignoreEventId));
    }

    /**
     * Locks the venue and verifies availability; the caller then moves the event into a blocking state in the same
     * transaction, which makes the reservation (BR-10). Must run inside a transaction.
     */
    public Venue reserve(long venueId, List<TimeSlot> slots, Long ignoreEventId) {
        Venue v = venues.lockById(venueId).orElseThrow(() -> new NotFoundException("error.venue.notFound"));
        if (!v.isActive()) throw new ValidationException("error.venue.inactive");
        List<Booking> c = findConflicts(venueId, slots, ignoreEventId);
        if (!c.isEmpty()) throw new VenueConflictException("error.venue.conflict", c);
        return v;
    }

    /** Loads a venue inside the caller's transaction; fails if it does not exist. */
    public Venue checkAvailability0(long venueId) {
        return venues.findById(venueId).orElseThrow(() -> new NotFoundException("error.venue.notFound"));
    }

    /** Active venue names for the public list and filters; no session needed. */
    public List<Venue> publicList() { return tx.inTransaction(() -> venues.findAll(true)); }
}

// ======== service.notification ========

/** Observer that records security-relevant domain events (FR-USR-08) and lets Admins read the log. */
class AuditService implements DomainEventListener {
    private final TransactionManager tx;
    private final AuditRepository repo;
    private final AuthorizationService authz;
    private final Clock clock;

    public AuditService(TransactionManager tx, AuditRepository repo, AuthorizationService authz, Clock clock) {
        this.tx = tx; this.repo = repo; this.authz = authz; this.clock = clock;
    }

    @Override public void handle(DomainEvent event) {
        DomainEvent.AuditSpec a = event.audit();
        if (a != null) repo.save(a.actorId(), a.action(), a.target(), a.details(), clock.instant());
    }

    /** Admin only: filtered audit log (FR-USR-08). */
    public List<AuditEntry> search(UserSession s, Instant from, Instant to, String actor, String action, int limit) {
        authz.require(s, Permission.VIEW_AUDIT);
        return tx.inTransaction(() -> repo.search(from, to, actor, action, limit));
    }
}

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

/** System settings changed by Admins (FR-USR-09): session timeout and data-retention period. */
class SettingsService {
    public static final String TIMEOUT = "session.timeout.minutes";
    public static final String RETENTION = "retention.years";
    private final TransactionManager tx;
    private final CatalogRepository catalog;
    private final AuthorizationService authz;
    private final SessionManager sessions;
    private final DomainEventPublisher publisher;

    public SettingsService(TransactionManager tx, CatalogRepository catalog, AuthorizationService authz, SessionManager sessions, DomainEventPublisher publisher) {
        this.tx = tx; this.catalog = catalog; this.authz = authz; this.sessions = sessions; this.publisher = publisher;
    }

    public int getInt(UserSession s, String key, int def) {
        authz.require(s, Permission.CHANGE_SETTINGS);
        return tx.inTransaction(() -> catalog.getSetting(key).map(Integer::parseInt).orElse(def));
    }

    /** Sets a numeric setting between 1 and 600 and audits the change. */
    public void setInt(UserSession s, String key, int value) {
        authz.require(s, Permission.CHANGE_SETTINGS);
        if (!key.equals(TIMEOUT) && !key.equals(RETENTION)) throw new ValidationException("error.setting.unknown");
        if (value < 1 || value > 600) throw new ValidationException("error.setting.range");
        tx.runInTransaction(() -> { catalog.setSetting(key, String.valueOf(value)); publisher.publish(new SettingChanged(s.getUserId(), key, String.valueOf(value))); });
        if (key.equals(TIMEOUT)) sessions.setTimeout(Duration.ofMinutes(value));
    }
}
