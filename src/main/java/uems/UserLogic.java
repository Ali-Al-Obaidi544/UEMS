package uems;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import static uems.DomainEvent.spec;
import static uems.Permission.*;

// MODULE 1 - USERS, SECURITY AND INFRASTRUCTURE (logic)
// Contains the domain classes (accounts, permissions, venues, shared exceptions and domain-event base), JDBC infrastructure and user/venue/audit repositories, and the services for authentication, sessions, authorization, users, venues, audit log and settings.
// Layers in this file, in order: INFRASTRUCTURE (module 1 only), DOMAIN, PERSISTENCE, SERVICES.

// ========== INFRASTRUCTURE ==========

/** Normalizes text for bilingual search: case-folding, diacritics removal and letter-variant unification (FR-SRC-06). */
final class ArabicNormalizer {
    private ArabicNormalizer() {}

    /** Returns a lower-case form without Arabic diacritics/tatweel, with alef, yaa and taa-marbuta variants unified. */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if ((c >= '\u064B' && c <= '\u065F') || c == '\u0670' || c == '\u0640') continue;
            switch (c) {
                case '\u0622', '\u0623', '\u0625', '\u0671' -> sb.append('\u0627');
                case '\u0649' -> sb.append('\u064A');
                case '\u0629' -> sb.append('\u0647');
                default -> {
                    if (c >= '\u0660' && c <= '\u0669') sb.append((char) ('0' + (c - '\u0660')));
                    else sb.append(c);
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }
}

/** Application configuration loaded from a properties file kept outside version control (NFR-SEC-06). */
final class Config {
    private final Properties props;

    public Config(Properties props) { this.props = props; }

    /** Loads configuration from the given file. */
    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return new Config(p);
    }

    /** Returns the value for a key or the default. */
    public String get(String key, String def) { return props.getProperty(key, def); }

    /** Returns the value for a key; fails if it is missing. */
    public String require(String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing configuration key: " + key);
        return v.trim();
    }

    /** Returns an integer value or the default. */
    public int getInt(String key, int def) {
        String v = props.getProperty(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }
}

/** A stored PBKDF2 password hash: iteration count, salt and derived key (NFR-SEC-01). */
record PasswordHash(int iterations, byte[] salt, byte[] hash) {
    /** Encodes as {@code pbkdf2-sha256$iterations$salt$hash} for storage. */
    public String encode() {
        Base64.Encoder e = Base64.getEncoder();
        return "pbkdf2-sha256$" + iterations + "$" + e.encodeToString(salt) + "$" + e.encodeToString(hash);
    }

    /** Parses the stored form produced by {@link #encode()}. */
    public static PasswordHash decode(String s) {
        String[] p = s.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) throw new IllegalArgumentException("Bad hash format");
        Base64.Decoder d = Base64.getDecoder();
        return new PasswordHash(Integer.parseInt(p[1]), d.decode(p[2]), d.decode(p[3]));
    }
}

/** Salted PBKDF2-HMAC-SHA256 password hashing with constant-time verification (FR-AUT-02, NFR-SEC-01). */
final class PasswordHasher {
    /** OWASP-recommended iteration count at the time of writing. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() { this(DEFAULT_ITERATIONS); }
    public PasswordHasher(int iterations) { this.iterations = iterations; }

    /** Hashes a password with a fresh random salt. The caller's array is wiped afterwards. */
    public PasswordHash hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        try { return new PasswordHash(iterations, salt, derive(password, salt, iterations)); }
        finally { Arrays.fill(password, '\0'); }
    }

    /** Verifies a password using the iteration count and salt stored with the hash. The array is wiped afterwards. */
    public boolean verify(char[] password, PasswordHash stored) {
        try {
            byte[] actual = derive(password, stored.salt(), stored.iterations());
            return MessageDigest.isEqual(actual, stored.hash());
        } finally { Arrays.fill(password, '\0'); }
    }

    private static byte[] derive(char[] pw, byte[] salt, int iter) {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}

/** Generates random session tokens (128 bit). */
final class SecureTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SecureTokens() {}

    /** Returns a new random 128-bit token as hex. */
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}

// ========== DOMAIN ==========

/** The account is temporarily locked after repeated failed logins (FR-AUT-06). */
class AccountLockedException extends AuthorizationException {
    public AccountLockedException(Instant until) { super("error.login.locked", until); }
}

/** The student already has an active registration for the event (FR-REG-03). */
class AlreadyRegisteredException extends ConflictException {
    public AlreadyRegisteredException(String key, Object... args) { super(key, args); }
}

/** The caller lacks permission, or the session is invalid. */
class AuthorizationException extends UemsException {
    public AuthorizationException(String key, Object... args) { super(key, args); }
}

/** The cancellation deadline has passed (BR-12). */
class CancellationClosedException extends ConflictException {
    public CancellationClosedException(String key, Object... args) { super(key, args); }
}

/** The operation conflicts with the current data state. */
class ConflictException extends UemsException {
    public ConflictException(String key, Object... args) { super(key, args); }
}

/** The database could not be reached or a statement failed (NFR-REL-03). */
class DataAccessException extends UemsException {
    public DataAccessException(String key, Object... args) { super(key, args); }
}

/** Gulf Standard Time (UTC+4): the display zone. All stored times are UTC (BR-13). */
final class Gst {
    public static final ZoneId ZONE = ZoneId.of("Asia/Dubai");
    private Gst() {}
}

/** A lifecycle transition is not allowed in the current state. */
class IllegalTransitionException extends ConflictException {
    public IllegalTransitionException(String key, Object... args) { super(key, args); }
}

/** Wrong university ID or password; deliberately does not say which (FR-AUT-05). */
class InvalidCredentialsException extends AuthorizationException {
    public InvalidCredentialsException(String key, Object... args) { super(key, args); }
}

/** Supported user-interface languages. */
enum Language {
    EN("en"), AR("ar");
    private final String code;
    Language(String code) { this.code = code; }
    /** Two-letter code stored in the database. */
    public String code() { return code; }
    /** The matching {@link Locale}. */
    public Locale locale() { return Locale.forLanguageTag(code); }
    /** Parses a stored code; defaults to English. */
    public static Language of(String code) { return "ar".equalsIgnoreCase(code) ? AR : EN; }
}

/** A text with an English and an optional Arabic version; at least one is required (FR-LOC-04). */
record LocalizedText(String en, String ar) {
    public static final LocalizedText EMPTY = new LocalizedText("", "");

    public LocalizedText { en = en == null ? "" : en.trim(); ar = ar == null ? "" : ar.trim(); }

    public static LocalizedText of(String en) { return new LocalizedText(en, ""); }

    /** Returns the text for the language, falling back to the other language when empty. */
    public String get(Language lang) {
        if (lang == Language.AR) return !ar.isEmpty() ? ar : en;
        return !en.isEmpty() ? en : ar;
    }

    /** True when both languages are empty. */
    public boolean isBlank() { return en.isEmpty() && ar.isEmpty(); }

    /** Encodes for use as a language-independent notification parameter. */
    public String encode() { return "\u0002" + en + "\u0001" + ar; }

    /** Decodes a value produced by {@link #encode()}; returns null when the string is a plain value. */
    public static LocalizedText decode(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '\u0002') return null;
        int i = s.indexOf('\u0001');
        return new LocalizedText(s.substring(1, i), s.substring(i + 1));
    }
}

/** A requested record does not exist. */
class NotFoundException extends UemsException {
    public NotFoundException(String key, Object... args) { super(key, args); }
}

/** Registration is not possible because the event is not Open (BR-12). */
class NotOpenException extends ConflictException {
    public NotOpenException(String key, Object... args) { super(key, args); }
}

/** One page of a paginated result. */
record Page<T>(List<T> items, int page, int pageSize, long total) {
    /** Number of pages (at least 1). */
    public int pageCount() { return (int) Math.max(1, (total + pageSize - 1) / pageSize); }
}

/** Composite component: anything that occupies time. Events are leaves, workshops are composites of sessions. */
interface Schedulable {
    /** Returns a non-empty, chronologically ordered list of time slots. */
    List<TimeSlot> getTimeSlots();
}

/** Registration refused because it clashes with another registration (FR-CLS-02). */
class ScheduleClashException extends ConflictException {
    public ScheduleClashException(LocalizedText otherEvent, TimeSlot slot) { super("error.clash", otherEvent, slot); }
}

/** The session timed out or was ended. */
class SessionExpiredException extends AuthorizationException {
    public SessionExpiredException(String key, Object... args) { super(key, args); }
}

/** The record was changed by someone else (optimistic locking). */
class StaleDataException extends ConflictException {
    public StaleDataException(String key, Object... args) { super(key, args); }
}

/** A half-open time interval. Two slots clash when each starts before the other ends; touching slots do not clash (BR-07). */
record TimeSlot(Instant start, Instant end) {
    public TimeSlot {
        if (start == null || end == null || !start.isBefore(end))
            throw new ValidationException("error.slot.invalid");
    }

    /** True when this slot overlaps the other (BR-07). */
    public boolean overlaps(TimeSlot o) { return start.isBefore(o.end) && o.start.isBefore(end); }

    /** Finds the first pair of overlapping slots between two lists: {a-slot, b-slot}. */
    public static Optional<TimeSlot[]> firstOverlap(List<TimeSlot> a, List<TimeSlot> b) {
        for (TimeSlot x : a) for (TimeSlot y : b) if (x.overlaps(y)) return Optional.of(new TimeSlot[]{x, y});
        return Optional.empty();
    }
}

/** Base of all application exceptions. Carries a message key and arguments so presenters can localize it (NFR-USA-03). */
class UemsException extends RuntimeException {
    private final String messageKey;
    private final transient Object[] args;

    public UemsException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }
    public UemsException(String messageKey, Throwable cause, Object... args) {
        super(messageKey, cause);
        this.messageKey = messageKey;
        this.args = args;
    }
    /** Resource-bundle key of the user-facing message. */
    public String getMessageKey() { return messageKey; }
    /** Arguments for the message template. */
    public Object[] getArgs() { return args; }
}

/** Input or state violates a validation rule. */
class ValidationException extends UemsException {
    public ValidationException(String key, Object... args) { super(key, args); }
}

/** The venue is not available for the requested time slots (BR-10). */
class VenueConflictException extends ConflictException {
    public VenueConflictException(String key, Object... args) { super(key, args); }
}

/** The three account types; Club Officer is a position inside a club, not an account type (BR-01). */
enum AccountType {
    STUDENT(EnumSet.of(BROWSE, REGISTER, CALENDAR, RECOMMENDATIONS, JOIN_CLUB, COMMENT, GIVE_FEEDBACK)),
    STAFF(EnumSet.of(BROWSE, CALENDAR, COMMENT, MANAGE_EVENTS, APPROVE_PROPOSALS, CHECK_IN, RECORD_RESULTS,
            SEND_ANNOUNCEMENT, VIEW_FEEDBACK, EDIT_DRAFT_SESSIONS, MANAGE_CLUBS, MODERATE_COMMENTS)),
    ADMIN(EnumSet.of(BROWSE, CALENDAR, COMMENT, MANAGE_USERS, MANAGE_VENUES, VIEW_AUDIT, CHANGE_SETTINGS));

    private final Set<Permission> base;
    AccountType(Set<Permission> base) { this.base = base; }

    /** Permissions an account of this type holds regardless of club positions. */
    public Set<Permission> basePermissions() { return base; }
}

/** A system administrator: manages users, venues and settings; no event privileges (BR-02). */
class Administrator extends User {
    @Override public AccountType accountType() { return AccountType.ADMIN; }
}

/** Fine-grained permissions derived from the SRS permission matrix (Table 3). */
enum Permission {
    VIEW_PUBLIC, BROWSE, REGISTER, CALENDAR, RECOMMENDATIONS, JOIN_CLUB, COMMENT, GIVE_FEEDBACK,
    // club-scoped (Officer) permissions; Staff hold them globally
    PROPOSE_EVENT(true), EDIT_DRAFT_SESSIONS(true), CHECK_IN(true), RECORD_RESULTS(true), SEND_ANNOUNCEMENT(true),
    VIEW_FEEDBACK_ANONYMOUS(true), DECIDE_JOIN_REQUESTS(true),
    // Staff
    MANAGE_EVENTS, APPROVE_PROPOSALS, VIEW_FEEDBACK, MANAGE_CLUBS, MODERATE_COMMENTS,
    // Admin
    MANAGE_USERS, MANAGE_VENUES, VIEW_AUDIT, CHANGE_SETTINGS;

    private final boolean clubScoped;
    Permission() { this(false); }
    Permission(boolean clubScoped) { this.clubScoped = clubScoped; }
    /** True when an officer may hold this permission for his or her own clubs (BR-14). */
    public boolean isClubScoped() { return clubScoped; }
}

/** A staff member: manages events and clubs; cannot hold an officer position (BR-01). */
class StaffMember extends User {
    @Override public AccountType accountType() { return AccountType.STAFF; }
}

/** A university student: registers for events, joins clubs and may become a club officer. */
class Student extends User {
    @Override public AccountType accountType() { return AccountType.STUDENT; }
}

/** Abstract user account; subclasses fix the account type. Answers permission questions for the base rights. */
abstract class User {
    private long id;
    private String universityId;
    private LocalizedText fullName = LocalizedText.EMPTY;
    private String email = "";
    private String faculty = "";
    private Language language = Language.EN;
    private PasswordHash passwordHash;
    private boolean mustChangePassword;
    private UserStatus status = UserStatus.ACTIVE;
    private int failedAttempts;
    private Instant lockedUntil;

    /** The fixed account type of this subclass. */
    public abstract AccountType accountType();

    /** Creates the right subclass for a type. */
    public static User create(AccountType type) {
        return switch (type) { case STUDENT -> new Student(); case STAFF -> new StaffMember(); case ADMIN -> new Administrator(); };
    }

    /** True when the base rights of the account type include the permission. Club-scoped rights are checked separately. */
    public boolean canPerform(Permission p) { return accountType().basePermissions().contains(p); }

    /** True when the account is currently locked after failed logins. */
    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getUniversityId() { return universityId; }
    public void setUniversityId(String v) { this.universityId = v; }
    public LocalizedText getFullName() { return fullName; }
    public void setFullName(LocalizedText v) { this.fullName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getFaculty() { return faculty; }
    public void setFaculty(String v) { this.faculty = v == null ? "" : v; }
    public Language getLanguage() { return language; }
    public void setLanguage(Language v) { this.language = v; }
    public PasswordHash getPasswordHash() { return passwordHash; }
    public void setPasswordHash(PasswordHash v) { this.passwordHash = v; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean v) { this.mustChangePassword = v; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus v) { this.status = v; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int v) { this.failedAttempts = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
}

/** An authenticated session identified by a random 128-bit token; kept in memory only (NFR-SEC-02). */
final class UserSession {
    private final String token;
    private final long userId;
    private final String universityId;
    private final AccountType accountType;
    private final LocalizedText displayName;
    private final Instant startedAt;
    private volatile Language language;
    private volatile boolean mustChangePassword;

    public UserSession(String token, User user, Instant startedAt) {
        this.token = token;
        this.userId = user.getId();
        this.universityId = user.getUniversityId();
        this.accountType = user.accountType();
        this.displayName = user.getFullName();
        this.language = user.getLanguage();
        this.mustChangePassword = user.isMustChangePassword();
        this.startedAt = startedAt;
    }
    public String getToken() { return token; }
    public long getUserId() { return userId; }
    public String getUniversityId() { return universityId; }
    public AccountType getAccountType() { return accountType; }
    public LocalizedText getDisplayName() { return displayName; }
    public Instant getStartedAt() { return startedAt; }
    public Language getLanguage() { return language; }
    public void setLanguage(Language l) { this.language = l; }
    /** True while the user must change the password before using other functions (FR-AUT-08). */
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean b) { this.mustChangePassword = b; }
}

/** Account status. Disabled users cannot log in but keep their history (FR-USR-03). */
enum UserStatus { ACTIVE, DISABLED, ANONYMIZED }

/** A venue booking: a published event (or one workshop session) occupying a slot (FR-VEN-03, FR-VEN-06). */
record Booking(long eventId, LocalizedText eventTitle, TimeSlot slot) {}

/** A room or hall that can host events; availability is derived from published events (FR-VEN-03). */
class Venue {
    private long id;
    private LocalizedText name = LocalizedText.EMPTY;
    private String building = "";
    private String room = "";
    private int capacity;
    private String facilities = "";
    private boolean active = true;
    private int version;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public LocalizedText getName() { return name; }
    public void setName(LocalizedText n) { this.name = n; }
    public String getBuilding() { return building; }
    public void setBuilding(String b) { this.building = b == null ? "" : b; }
    public String getRoom() { return room; }
    public void setRoom(String r) { this.room = r == null ? "" : r; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int c) { this.capacity = c; }
    public String getFacilities() { return facilities; }
    public void setFacilities(String f) { this.facilities = f == null ? "" : f; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
}

/** An administrator created, edited, disabled, re-enabled, reset or anonymized an account (audit). */
record AccountChanged(long actorId, long targetUserId, String action, String details) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, action, "user:" + targetUserId, details); }
}

/** An account was locked after repeated failures (FR-AUT-06). */
record AccountLocked(String universityId, Instant until) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(null, "ACCOUNT_LOCKED", universityId, "until=" + until); }
}

/** An account type changed (NT-12 to the affected user, plus audit). */
record AccountTypeChanged(long actorId, long userId, AccountType from, AccountType to) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-12", "notif.account.typeChanged", null, Params.key("accountType." + to.name())));
    }
    @Override public AuditSpec audit() { return new AuditSpec(actorId, "ROLE_CHANGE", "user:" + userId, from + "->" + to); }
}

/**
 * Observer pattern: an immutable fact that happened. Services publish these; the notification and audit listeners
 * turn them into notifications (Table 8) and audit rows without the publisher knowing about them.
 * A new notification trigger is a new implementing class, with no change to any service (NFR-MNT-02).
 */
interface DomainEvent {
    /** Notifications this event causes; none by default. */
    default List<NotificationSpec> notifications() { return List.of(); }
    /** Audit entry this event causes; null when it is not security-relevant (FR-USR-08). */
    default AuditSpec audit() { return null; }

    /** Audit data: actor (may be null), action code, target and details. */
    record AuditSpec(Long actorId, String action, String target, String details) {}

    /** Convenience factory for a notification spec. */
    static NotificationSpec spec(long userId, String type, String key, String link, String... params) {
        return new NotificationSpec(userId, type, key, List.of(params), link);
    }
}

/** Observer: reacts to domain events delivered synchronously inside the publisher's transaction. */
interface DomainEventListener {
    void handle(DomainEvent event);
}

/**
 * Subject of the Observer pattern. Delivery is synchronous on the caller's thread and inside the caller's
 * transaction, so a failing listener rolls the operation back (NFR-REL-01).
 */
class DomainEventPublisher {
    private record Subscription(Class<? extends DomainEvent> type, DomainEventListener listener) {}
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /** Subscribes a listener to events of the given type (or subtypes). */
    public void subscribe(Class<? extends DomainEvent> type, DomainEventListener listener) {
        subscriptions.add(new Subscription(type, listener));
    }

    /** Delivers the event to all matching listeners. */
    public void publish(DomainEvent event) {
        for (Subscription s : subscriptions) if (s.type().isInstance(event)) s.listener().handle(event);
    }
}

/** A login attempt failed (FR-USR-08, NFR-SEC-07). */
record LoginFailed(String universityId, int attempts) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(null, "LOGIN_FAILED", universityId, "attempts=" + attempts); }
}

/** A system setting was changed by an administrator (audit). */
record SettingChanged(long actorId, String key, String value) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, "SETTING_CHANGE", key, value); }
}

/** A user logged in (audit). */
record UserLoggedIn(long userId, String universityId) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(userId, "LOGIN", universityId, null); }
}

/** Persistence contract for the audit log. */
interface AuditRepository {
    void save(Long actorId, String action, String target, String details, Instant at);
    /** Filters by optional date range, actor text (id or name) and action. */
    List<AuditEntry> search(Instant from, Instant to, String actorText, String action, int limit);
}

/**
 * Unit-of-work boundary. Commits on return, rolls back on exception, and retries up to three times on deadlock or
 * serialization failure. A nested call joins the running transaction (NFR-REL-01).
 */
interface TransactionManager {
    <T> T inTransaction(Callable<T> work);
    /** Same for work that returns nothing. */
    void runInTransaction(Runnable work);
}

/** Persistence contract for user accounts. */
interface UserRepository {
    Optional<User> findById(long id);
    Optional<User> findByUniversityId(String universityId);
    /** SELECT ... FOR UPDATE; requires a transaction. */
    Optional<User> lockById(long id);
    Optional<User> lockByUniversityId(String universityId);
    /** Inserts when the id is 0, otherwise updates. */
    User save(User user);
    List<User> search(String text, AccountType type, UserStatus status, int limit);
    int countActiveAdmins();
    Set<Long> findInterests(long userId);
    void saveInterests(long userId, Set<Long> tagIds);
    List<Long> findActiveStaffIds();
    Map<Long, User> findByIds(Collection<Long> ids);
}

/** Persistence contract for venues. */
interface VenueRepository {
    Optional<Venue> findById(long id);
    /** Locks the venue row so concurrent publications of the same venue are serialized (BR-10). */
    Optional<Venue> lockById(long id);
    List<Venue> findAll(boolean activeOnly);
    Venue save(Venue v);
}

// ========== PERSISTENCE ==========

/** A small JDK-only connection pool; each client needs only a few connections (Design Table 3). */
final class ConnectionPool implements AutoCloseable {
    private final String url, user, password;
    private final Semaphore permits;
    private final Deque<Connection> idle = new ArrayDeque<>();

    public ConnectionPool(String url, String user, String password, int size) {
        this.url = url; this.user = user; this.password = password;
        this.permits = new Semaphore(size);
    }

    /** Borrows a valid connection, waiting up to 15 s. */
    public Connection borrow() {
        try {
            if (!permits.tryAcquire(15, TimeUnit.SECONDS)) throw new DataAccessException("error.db.busy");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DataAccessException("error.db.busy"); }
        try {
            while (true) {
                Connection c;
                synchronized (idle) { c = idle.poll(); }
                if (c == null) return DriverManager.getConnection(url, user, password);
                if (c.isValid(2)) return c;
                try { c.close(); } catch (SQLException ignored) { }
            }
        } catch (SQLException e) {
            permits.release();
            throw new DataAccessException("error.db.unreachable", e);
        }
    }

    /** Returns a connection to the pool. */
    public void release(Connection c) {
        try {
            if (c.isClosed()) { permits.release(); return; }
            c.setAutoCommit(true);
            synchronized (idle) { idle.push(c); }
        } catch (SQLException e) { try { c.close(); } catch (SQLException ignored) { } }
        finally { permits.release(); }
    }

    @Override public void close() {
        synchronized (idle) {
            for (Connection c : idle) { try { c.close(); } catch (SQLException ignored) { } }
            idle.clear();
        }
    }
}

/**
 * Thin helper around JDBC for the repositories. All statements are PreparedStatements with bound parameters
 * (NFR-SEC-04); SQL text is built only from fixed fragments.
 */
final class Jdbc {
    /** Maps one result row. */
    @FunctionalInterface public interface Mapper<T> { T map(ResultSet rs) throws SQLException; }

    private final JdbcTransactionManager tm;
    public Jdbc(JdbcTransactionManager tm) { this.tm = tm; }
    public SqlDialect dialect() { return tm.dialect(); }

    public <T> List<T> list(String sql, Mapper<T> m, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(m.map(rs));
            return out;
        } catch (SQLException e) { throw wrap(e); }
    }

    public <T> Optional<T> one(String sql, Mapper<T> m, Object... args) {
        List<T> l = list(sql, m, args);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Executes an update; returns the number of rows changed. */
    public int exec(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args)) { return ps.executeUpdate(); }
        catch (SQLException e) { throw wrap(e); }
    }

    /** Executes an INSERT ... RETURNING id and returns the id. */
    public long insert(String sqlReturningId, Object... args) {
        return list(sqlReturningId, rs -> rs.getLong(1), args).get(0);
    }

    public long count(String sql, Object... args) { return list(sql, rs -> rs.getLong(1), args).get(0); }

    private PreparedStatement prepare(String sql, Object[] args) throws SQLException {
        Connection c = tm.connection();
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) bind(c, ps, i + 1, args[i]);
        } catch (SQLException | RuntimeException e) { ps.close(); throw e; }
        return ps;
    }

    private static void bind(Connection c, PreparedStatement ps, int i, Object v) throws SQLException {
        if (v == null) ps.setObject(i, null);
        else if (v instanceof Instant t) ps.setObject(i, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
        else if (v instanceof Enum<?> e) ps.setString(i, e.name());
        else if (v instanceof byte[] b) ps.setBytes(i, b);
        else if (v instanceof Collection<?> col) {
            Object[] arr = col.toArray();
            String type = arr.length > 0 && arr[0] instanceof String ? "text" : "bigint";
            Array a = c.createArrayOf(type, arr);
            ps.setArray(i, a);
        } else ps.setObject(i, v);
    }

    public DataAccessException wrap(SQLException e) { return new DataAccessException("error.db", e); }

    /** True when the exception chain contains a unique-constraint violation. */
    public boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException s && dialect().isUniqueViolation(s)) return true;
        return false;
    }

    // ---- row helpers ----
    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}

/** JDBC implementation of {@link AuditRepository}. */
class JdbcAuditRepository implements AuditRepository {
    private final Jdbc db;
    public JdbcAuditRepository(Jdbc db) { this.db = db; }

    @Override public void save(Long actorId, String action, String target, String details, Instant at) {
        db.exec("INSERT INTO audit_log (actor_id, action, target, details, created_at) VALUES (?,?,?,?,?)", actorId, action, target, details, at);
    }

    @Override public List<AuditEntry> search(Instant from, Instant to, String actorText, String action, int limit) {
        StringBuilder sql = new StringBuilder("SELECT a.audit_id, a.actor_id, COALESCE(u.university_id,'') || ' ' || COALESCE(u.full_name_en,''), a.action, a.target, a.details, a.created_at "
                + "FROM audit_log a LEFT JOIN app_user u ON u.user_id=a.actor_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) { sql.append(" AND a.created_at>=?"); args.add(from); }
        if (to != null) { sql.append(" AND a.created_at<?"); args.add(to); }
        if (actorText != null && !actorText.isBlank()) {
            String like = "%" + actorText.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (u.university_id").append(db.dialect().ilike()).append("? OR u.full_name_en").append(db.dialect().ilike()).append("?)");
            args.add(like); args.add(like);
        }
        if (action != null && !action.isBlank()) { sql.append(" AND a.action=?"); args.add(action.trim()); }
        sql.append(" ORDER BY a.created_at DESC, a.audit_id DESC LIMIT ?");
        args.add(limit);
        return db.list(sql.toString(), rs -> new AuditEntry(rs.getLong(1), Jdbc.longOrNull(rs, "actor_id"), rs.getString(3).trim(), rs.getString(4), rs.getString(5), rs.getString(6), Jdbc.instant(rs, "created_at")), args.toArray());
    }
}

/**
 * JDBC unit of work bound to the current thread. Commits on return, rolls back on exception, and retries up to
 * three more times on deadlock/serialization failures (Design Table 5). Nested calls join the running transaction.
 */
final class JdbcTransactionManager implements TransactionManager {
    private static final int MAX_RETRIES = 3;
    private final ConnectionPool pool;
    private final SqlDialect dialect;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public JdbcTransactionManager(ConnectionPool pool, SqlDialect dialect) { this.pool = pool; this.dialect = dialect; }

    /** The connection of the running transaction; repositories must be called inside a transaction. */
    Connection connection() {
        Connection c = current.get();
        if (c == null) throw new IllegalStateException("No transaction active");
        return c;
    }
    SqlDialect dialect() { return dialect; }

    @Override public <T> T inTransaction(Callable<T> work) {
        if (current.get() != null) return call(work);
        int attempt = 0;
        while (true) {
            Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                current.set(c);
                T result = call(work);
                c.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                if (isRetryable(e) && ++attempt <= MAX_RETRIES) continue;
                if (e instanceof RuntimeException re) throw re;
                throw new DataAccessException("error.db", e);
            } finally {
                current.remove();
                pool.release(c);
            }
        }
    }

    @Override public void runInTransaction(Runnable work) {
        inTransaction(() -> { work.run(); return null; });
    }

    private static <T> T call(Callable<T> work) {
        try { return work.call(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new DataAccessException("error.db", e); }
    }

    private boolean isRetryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException s && dialect.isRetryable(s)) return true;
        return false;
    }
}

/** JDBC implementation of {@link UserRepository}. */
class JdbcUserRepository implements UserRepository {
    private static final String COLS = "user_id, university_id, full_name_en, full_name_ar, email, account_type, faculty, language, "
            + "password_hash, must_change_password, status, failed_attempts, locked_until";
    private final Jdbc db;
    public JdbcUserRepository(Jdbc db) { this.db = db; }

    private static User map(ResultSet rs) throws SQLException {
        User u = User.create(AccountType.valueOf(rs.getString("account_type")));
        u.setId(rs.getLong("user_id"));
        u.setUniversityId(rs.getString("university_id"));
        u.setFullName(new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")));
        u.setEmail(rs.getString("email"));
        u.setFaculty(rs.getString("faculty"));
        u.setLanguage(Language.of(rs.getString("language")));
        u.setPasswordHash(PasswordHash.decode(rs.getString("password_hash")));
        u.setMustChangePassword(rs.getBoolean("must_change_password"));
        u.setStatus(UserStatus.valueOf(rs.getString("status")));
        u.setFailedAttempts(rs.getInt("failed_attempts"));
        u.setLockedUntil(Jdbc.instant(rs, "locked_until"));
        return u;
    }

    @Override public Optional<User> findById(long id) { return db.one("SELECT " + COLS + " FROM app_user WHERE user_id=?", JdbcUserRepository::map, id); }
    @Override public Optional<User> findByUniversityId(String uid) { return db.one("SELECT " + COLS + " FROM app_user WHERE university_id=?", JdbcUserRepository::map, uid); }
    @Override public Optional<User> lockById(long id) { return db.one("SELECT " + COLS + " FROM app_user WHERE user_id=?" + db.dialect().forUpdate(), JdbcUserRepository::map, id); }
    @Override public Optional<User> lockByUniversityId(String uid) { return db.one("SELECT " + COLS + " FROM app_user WHERE university_id=?" + db.dialect().forUpdate(), JdbcUserRepository::map, uid); }

    @Override public User save(User u) {
        Object[] vals = {u.getUniversityId(), u.getFullName().en(), blankToNull(u.getFullName().ar()), u.getEmail(), u.accountType(),
                u.getFaculty(), u.getLanguage().code(), u.getPasswordHash().encode(), u.isMustChangePassword(), u.getStatus(),
                u.getFailedAttempts(), u.getLockedUntil()};
        if (u.getId() == 0) {
            u.setId(db.insert("INSERT INTO app_user (university_id, full_name_en, full_name_ar, email, account_type, faculty, language, "
                    + "password_hash, must_change_password, status, failed_attempts, locked_until) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING user_id", vals));
        } else {
            Object[] a = Arrays.copyOf(vals, vals.length + 1);
            a[vals.length] = u.getId();
            db.exec("UPDATE app_user SET university_id=?, full_name_en=?, full_name_ar=?, email=?, account_type=?, faculty=?, language=?, "
                    + "password_hash=?, must_change_password=?, status=?, failed_attempts=?, locked_until=? WHERE user_id=?", a);
        }
        return u;
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    @Override public List<User> search(String text, AccountType type, UserStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM app_user WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (university_id").append(db.dialect().ilike()).append("? OR full_name_en").append(db.dialect().ilike())
               .append("? OR COALESCE(full_name_ar,'')").append(db.dialect().ilike()).append("?)");
            args.add(like); args.add(like); args.add(like);
        }
        if (type != null) { sql.append(" AND account_type=?"); args.add(type); }
        if (status != null) { sql.append(" AND status=?"); args.add(status); }
        sql.append(" ORDER BY university_id LIMIT ?");
        args.add(limit);
        return db.list(sql.toString(), JdbcUserRepository::map, args.toArray());
    }

    @Override public int countActiveAdmins() {
        return (int) db.count("SELECT count(*) FROM app_user WHERE account_type='ADMIN' AND status='ACTIVE'");
    }
    @Override public Set<Long> findInterests(long userId) {
        return new HashSet<>(db.list("SELECT tag_id FROM user_interest WHERE user_id=?", rs -> rs.getLong(1), userId));
    }
    @Override public void saveInterests(long userId, Set<Long> tagIds) {
        db.exec("DELETE FROM user_interest WHERE user_id=?", userId);
        for (Long t : tagIds) db.exec("INSERT INTO user_interest (user_id, tag_id) VALUES (?,?)", userId, t);
    }
    @Override public List<Long> findActiveStaffIds() {
        return db.list("SELECT user_id FROM app_user WHERE account_type='STAFF' AND status='ACTIVE'", rs -> rs.getLong(1));
    }
    @Override public Map<Long, User> findByIds(Collection<Long> ids) {
        Map<Long, User> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (User u : db.list("SELECT " + COLS + " FROM app_user WHERE user_id = ANY(?)", JdbcUserRepository::map, ids)) m.put(u.getId(), u);
        return m;
    }
}

/** JDBC implementation of {@link VenueRepository}. */
class JdbcVenueRepository implements VenueRepository {
    private static final String COLS = "venue_id, name_en, name_ar, building, room, capacity, facilities, active, version";
    private final Jdbc db;
    public JdbcVenueRepository(Jdbc db) { this.db = db; }

    private static Venue map(ResultSet rs) throws SQLException {
        Venue v = new Venue();
        v.setId(rs.getLong("venue_id"));
        v.setName(new LocalizedText(rs.getString("name_en"), rs.getString("name_ar")));
        v.setBuilding(rs.getString("building")); v.setRoom(rs.getString("room"));
        v.setCapacity(rs.getInt("capacity")); v.setFacilities(rs.getString("facilities"));
        v.setActive(rs.getBoolean("active")); v.setVersion(rs.getInt("version"));
        return v;
    }
    @Override public Optional<Venue> findById(long id) { return db.one("SELECT " + COLS + " FROM venue WHERE venue_id=?", JdbcVenueRepository::map, id); }
    @Override public Optional<Venue> lockById(long id) { return db.one("SELECT " + COLS + " FROM venue WHERE venue_id=?" + db.dialect().forUpdate(), JdbcVenueRepository::map, id); }
    @Override public List<Venue> findAll(boolean activeOnly) {
        return db.list("SELECT " + COLS + " FROM venue" + (activeOnly ? " WHERE active" : "") + " ORDER BY name_en", JdbcVenueRepository::map);
    }
    @Override public Venue save(Venue v) {
        String ar = v.getName().ar().isEmpty() ? null : v.getName().ar();
        if (v.getId() == 0) {
            v.setId(db.insert("INSERT INTO venue (name_en, name_ar, building, room, capacity, facilities, active) VALUES (?,?,?,?,?,?,?) RETURNING venue_id",
                    v.getName().en(), ar, v.getBuilding(), v.getRoom(), v.getCapacity(), v.getFacilities(), v.isActive()));
        } else {
            int n = db.exec("UPDATE venue SET name_en=?, name_ar=?, building=?, room=?, capacity=?, facilities=?, active=?, version=version+1 WHERE venue_id=? AND version=?",
                    v.getName().en(), ar, v.getBuilding(), v.getRoom(), v.getCapacity(), v.getFacilities(), v.isActive(), v.getId(), v.getVersion());
            if (n == 0) throw new StaleDataException("error.stale");
            v.setVersion(v.getVersion() + 1);
        }
        return v;
    }
}

/** PostgreSQL 15+ dialect. */
class PostgresDialect implements SqlDialect {
    @Override public String forUpdate() { return " FOR UPDATE"; }
    @Override public String ilike() { return " ILIKE "; }
    @Override public boolean isRetryable(SQLException e) { return "40P01".equals(e.getSQLState()) || "40001".equals(e.getSQLState()); }
    @Override public boolean isUniqueViolation(SQLException e) { return "23505".equals(e.getSQLState()); }
    @Override public String insertIgnore(String sql, String target) { return sql + " ON CONFLICT (" + target + ") DO NOTHING"; }
}

/** Isolates the few database-product specifics so another relational database can be used (NFR-PRT-02). */
interface SqlDialect {
    /** Row-lock suffix for SELECT. */
    String forUpdate();
    /** Case-insensitive LIKE operator. */
    String ilike();
    /** True when the failure is a deadlock or serialization conflict that is worth retrying. */
    boolean isRetryable(SQLException e);
    /** True when the failure is a unique-constraint violation. */
    boolean isUniqueViolation(SQLException e);
    /** Statement that inserts and ignores conflicts on the primary key. */
    String insertIgnore(String insertSql, String conflictTarget);
}

/** Composition root of the persistence layer: builds the pool, transaction manager and all repositories. */
final class Persistence implements AutoCloseable {
    public final ConnectionPool pool;
    public final TransactionManager tx;
    public final UserRepository users;
    public final VenueRepository venues;
    public final EventRepository events;
    public final RegistrationRepository registrations;
    public final ClubRepository clubs;
    public final NotificationRepository notifications;
    public final AuditRepository audit;
    public final SocialRepository social;
    public final ResultRepository results;
    public final CatalogRepository catalog;
    public final SearchRepository search;
    public final SchedulerRepository scheduler;

    public Persistence(String url, String user, String password, int poolSize) {
        SqlDialect dialect = new PostgresDialect();
        pool = new ConnectionPool(url, user, password, poolSize);
        JdbcTransactionManager tm = new JdbcTransactionManager(pool, dialect);
        Jdbc db = new Jdbc(tm);
        tx = tm;
        users = new JdbcUserRepository(db);
        venues = new JdbcVenueRepository(db);
        JdbcEventRepository ev = new JdbcEventRepository(db);
        events = ev;
        registrations = new JdbcRegistrationRepository(db, ev);
        clubs = new JdbcClubRepository(db);
        notifications = new JdbcNotificationRepository(db);
        audit = new JdbcAuditRepository(db);
        social = new JdbcSocialRepository(db);
        results = new JdbcResultRepository(db);
        catalog = new JdbcCatalogRepository(db);
        search = new JdbcSearchRepository(db, ev);
        scheduler = new JdbcSchedulerRepository(db);
    }

    @Override public void close() { pool.close(); }
}

// ========== SERVICES ==========

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
