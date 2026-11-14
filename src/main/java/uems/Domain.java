package uems;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static uems.DomainEvent.spec;
import static uems.Permission.*;

// DOMAIN LAYER: entities, state machines, business rules, domain events, exceptions and the repository interfaces.
// Each class is a separate (package-private) top-level class; they share this file only to keep the project small.

// ======== domain.common ========

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

// ======== domain.user ========

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

// ======== domain.venue ========

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

// ======== domain.event ========

/** Base class: every transition is illegal unless a concrete state overrides it. */
abstract class AbstractEventState implements EventState {
    private IllegalTransitionException illegal(String action) {
        return new IllegalTransitionException("error.event.transition", action, status().name());
    }
    @Override public void publish(Event e) { throw illegal("publish"); }
    @Override public void open(Event e) { throw illegal("open"); }
    @Override public void close(Event e) { throw illegal("close"); }
    @Override public void start(Event e) { throw illegal("start"); }
    @Override public void complete(Event e) { throw illegal("complete"); }
    @Override public void cancel(Event e) { throw illegal("cancel"); }
    @Override public void submit(Event e) { throw illegal("submit"); }
    @Override public void approve(Event e) { throw illegal("approve"); }
    @Override public void reject(Event e) { throw illegal("reject"); }
    @Override public void withdraw(Event e) { throw illegal("withdraw"); }
}

/** Event lifecycle state: cancelled with a reason; kept for history. */
final class CancelledState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.CANCELLED; }

}

/** Event lifecycle state: registration closed, event not started. */
final class ClosedState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.CLOSED; }
    @Override public void start(Event e) { e.changeState(new OngoingState()); }
    @Override public void cancel(Event e) { e.changeState(new CancelledState()); }
}

/** A competition with individual registration; placements are recorded separately (FR-CMP-01). */
class Competition extends Event {
    private boolean resultsPublished;
    @Override public EventType type() { return EventType.COMPETITION; }
    public boolean isResultsPublished() { return resultsPublished; }
    public void setResultsPublished(boolean b) { this.resultsPublished = b; }
}

/** Event lifecycle state: finished; feedback and discussion windows apply. */
final class CompletedState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.COMPLETED; }

}

/** Event lifecycle state: being prepared; may be published directly (Staff) or submitted for approval (officer). */
final class DraftState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.DRAFT; }
    @Override public void publish(Event e) { e.changeState(new PublishedState()); }
    @Override public void submit(Event e) { e.changeState(new PendingApprovalState()); }
}

/**
 * Abstract event. Owns its lifecycle {@link EventState} and enforces the capacity and schedule rules
 * (rich domain model). Offers its time slots through {@link Schedulable}.
 */
abstract class Event implements Schedulable {
    public static final int MAX_TITLE = 200;
    public static final int MAX_DESCRIPTION = 4000;

    private long id;
    private LocalizedText title = LocalizedText.EMPTY;
    private LocalizedText description = LocalizedText.EMPTY;
    private Long categoryId;
    private Set<Long> tagIds = new HashSet<>();
    private Instant startAt;
    private Instant endAt;
    private Long venueId;
    private int capacity;
    private Long hostClubId;
    private Instant regOpensAt;
    private Instant regDeadlineAt;
    private Instant cancelDeadlineAt;
    private EventState state = new DraftState();
    private Long seriesId;
    private Integer seriesIndex;
    private long createdBy;
    private boolean proposal;
    private String rejectionReason;
    private String cancelReason;
    private int version;

    /** The concrete type of this event. */
    public abstract EventType type();

    // ---- lifecycle (delegates to the State object) ----
    public EventStatus status() { return state.status(); }
    void changeState(EventState next) { this.state = next; }
    /** Restores the state when loading from the database (no transition rules applied). */
    public void restoreState(EventStatus s) { this.state = EventStates.of(s); }
    public void publish() { state.publish(this); }
    public void open() { state.open(this); }
    public void close() { state.close(this); }
    public void start() { state.start(this); }
    public void complete() { state.complete(this); }
    public void cancel() { state.cancel(this); }
    public void submit() { state.submit(this); }
    public void approve() { state.approve(this); }
    public void reject() { state.reject(this); }
    public void withdraw() { state.withdraw(this); }

    // ---- schedule ----
    @Override public List<TimeSlot> getTimeSlots() { return List.of(new TimeSlot(startAt, endAt)); }

    /** Registration opening time; null means open as soon as published. */
    public Instant effectiveRegOpensAt(Instant publishTime) { return regOpensAt != null ? regOpensAt : publishTime; }
    /** Registration deadline; defaults to the start time (FR-LCY-07). */
    public Instant effectiveRegDeadline() { return regDeadlineAt != null ? regDeadlineAt : startAt; }
    /** Cancellation deadline; defaults to 24 hours before the start (BR-05). */
    public Instant effectiveCancelDeadline() {
        return cancelDeadlineAt != null ? cancelDeadlineAt : startAt.minus(Duration.ofHours(24));
    }

    /** Applies input data to this event (used for create and edit). */
    public void applyData(EventData d) {
        this.title = d.title; this.description = d.description; this.categoryId = d.categoryId;
        this.tagIds = new HashSet<>(d.tagIds);
        this.venueId = d.venueId; this.capacity = d.capacity; this.hostClubId = d.hostClubId;
        this.regOpensAt = d.regOpensAt; this.regDeadlineAt = d.regDeadlineAt; this.cancelDeadlineAt = d.cancelDeadlineAt;
        if (type() != EventType.WORKSHOP) { this.startAt = d.startAt; this.endAt = d.endAt; }
    }

    /** Checks the rules that always hold, even for drafts: text lengths, ordering of times, deadlines (BR-05). */
    public void validateBasics() {
        if (title.isBlank()) throw new ValidationException("error.event.titleRequired");
        if (title.en().length() > MAX_TITLE || title.ar().length() > MAX_TITLE)
            throw new ValidationException("error.text.tooLong", "title", MAX_TITLE);
        if (description.en().length() > MAX_DESCRIPTION || description.ar().length() > MAX_DESCRIPTION)
            throw new ValidationException("error.text.tooLong", "description", MAX_DESCRIPTION);
        if (capacity < 0) throw new ValidationException("error.event.capacity");
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) throw new ValidationException("error.event.endBeforeStart");
        if (startAt != null) {
            if (cancelDeadlineAt != null && cancelDeadlineAt.isAfter(startAt)) throw new ValidationException("error.event.cancelDeadline");
            if (regDeadlineAt != null && regDeadlineAt.isAfter(startAt)) throw new ValidationException("error.event.regDeadline");
        }
        if (regOpensAt != null && regDeadlineAt != null && regOpensAt.isAfter(regDeadlineAt))
            throw new ValidationException("error.event.regOpens");
    }

    /** Checks the publication rules (FR-EVT-03): future start, end after start, capacity 1..venue capacity. */
    public void validateForPublish(Instant now, int venueCapacity) {
        validateBasics();
        if (venueId == null) throw new ValidationException("error.event.venueRequired");
        if (startAt == null || endAt == null) throw new ValidationException("error.event.timesRequired");
        if (!startAt.isAfter(now)) throw new ValidationException("error.event.startPast");
        if (capacity < 1 || capacity > venueCapacity) throw new ValidationException("error.event.capacityRange", venueCapacity);
    }

    // ---- accessors ----
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public LocalizedText getTitle() { return title; }
    public void setTitle(LocalizedText t) { this.title = t; }
    public LocalizedText getDescription() { return description; }
    public void setDescription(LocalizedText d) { this.description = d; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long c) { this.categoryId = c; }
    public Set<Long> getTagIds() { return tagIds; }
    public void setTagIds(Set<Long> t) { this.tagIds = t; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant s) { this.startAt = s; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant e) { this.endAt = e; }
    public Long getVenueId() { return venueId; }
    public void setVenueId(Long v) { this.venueId = v; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int c) { this.capacity = c; }
    public Long getHostClubId() { return hostClubId; }
    public void setHostClubId(Long c) { this.hostClubId = c; }
    public Instant getRegOpensAt() { return regOpensAt; }
    public void setRegOpensAt(Instant t) { this.regOpensAt = t; }
    public Instant getRegDeadlineAt() { return regDeadlineAt; }
    public void setRegDeadlineAt(Instant t) { this.regDeadlineAt = t; }
    public Instant getCancelDeadlineAt() { return cancelDeadlineAt; }
    public void setCancelDeadlineAt(Instant t) { this.cancelDeadlineAt = t; }
    public Long getSeriesId() { return seriesId; }
    public void setSeriesId(Long s) { this.seriesId = s; }
    public Integer getSeriesIndex() { return seriesIndex; }
    public void setSeriesIndex(Integer i) { this.seriesIndex = i; }
    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long u) { this.createdBy = u; }
    public boolean isProposal() { return proposal; }
    public void setProposal(boolean p) { this.proposal = p; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String r) { this.rejectionReason = r; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String r) { this.cancelReason = r; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
}

/** Input data for creating or editing an event (parameter object, FR-EVT-02). */
class EventData {
    public EventType type = EventType.GENERAL;
    public LocalizedText title = LocalizedText.EMPTY;
    public LocalizedText description = LocalizedText.EMPTY;
    public Long categoryId;
    public Set<Long> tagIds = new HashSet<>();
    public Instant startAt;
    public Instant endAt;
    public Long venueId;
    public int capacity;
    public Long hostClubId;
    /** Null means "immediately when published". */
    public Instant regOpensAt;
    /** Null means "at the start time". */
    public Instant regDeadlineAt;
    /** Null means "24 hours before the start" (BR-05). */
    public Instant cancelDeadlineAt;
    /** Workshop sessions (null leaves the sessions unchanged on edit). */
    public List<SessionData> sessions;
    public byte[] poster;
    public String posterType;
    public boolean clearPoster;

    public EventData copy() {
        EventData d = new EventData();
        d.type = type; d.title = title; d.description = description; d.categoryId = categoryId;
        d.tagIds = new HashSet<>(tagIds); d.startAt = startAt; d.endAt = endAt; d.venueId = venueId;
        d.capacity = capacity; d.hostClubId = hostClubId; d.regOpensAt = regOpensAt;
        d.regDeadlineAt = regDeadlineAt; d.cancelDeadlineAt = cancelDeadlineAt;
        d.sessions = sessions == null ? null : new ArrayList<>(sessions);
        d.poster = poster; d.posterType = posterType; d.clearPoster = clearPoster;
        return d;
    }
}

/** Factory Method: creates the right {@link Event} subclass from a type and the entered data. */
final class EventFactory {
    private EventFactory() {}

    /** Creates a new Draft event of the data's type. */
    public static Event create(EventData data, long createdBy, boolean proposal) {
        Event e = switch (data.type) {
            case GENERAL -> new GeneralEvent();
            case WORKSHOP -> new Workshop();
            case COMPETITION -> new Competition();
        };
        e.applyData(data);
        e.setCreatedBy(createdBy);
        e.setProposal(proposal);
        e.restoreState(EventStatus.DRAFT);
        e.validateBasics();
        return e;
    }
}

/** The set of events generated from one recurrence rule (FR-REC-02). */
class EventSeries {
    private long id;
    private final RecurrenceRule rule;
    public EventSeries(RecurrenceRule rule) { this.rule = rule; }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public RecurrenceRule getRule() { return rule; }
}

/**
 * State pattern for the event lifecycle. Each concrete state allows only its legal transitions and
 * throws {@link IllegalTransitionException} for the others (FR-LCY-01).
 */
interface EventState {
    /** The persistent name of this state. */
    EventStatus status();
    void publish(Event e);
    void open(Event e);
    void close(Event e);
    void start(Event e);
    void complete(Event e);
    void cancel(Event e);
    void submit(Event e);
    void approve(Event e);
    void reject(Event e);
    void withdraw(Event e);
}

/** Maps persistent status values to state objects. */
final class EventStates {
    private EventStates() {}

    /** Returns a new state object for the given status. */
    public static EventState of(EventStatus s) {
        return switch (s) {
            case DRAFT -> new DraftState();
            case PENDING_APPROVAL -> new PendingApprovalState();
            case PUBLISHED -> new PublishedState();
            case OPEN -> new OpenState();
            case CLOSED -> new ClosedState();
            case ONGOING -> new OngoingState();
            case COMPLETED -> new CompletedState();
            case CANCELLED -> new CancelledState();
        };
    }
}

/** The eight lifecycle states of an event (FR-LCY-01). */
enum EventStatus {
    DRAFT, PENDING_APPROVAL, PUBLISHED, OPEN, CLOSED, ONGOING, COMPLETED, CANCELLED;

    /** True when events in this state reserve their venue (FR-VEN-03). */
    public boolean blocksVenue() { return this == PUBLISHED || this == OPEN || this == CLOSED || this == ONGOING; }

    /** True when students and guests may see events in this state. */
    public boolean isPublic() { return ordinal() >= PUBLISHED.ordinal(); }
}

/** The three event types (FR-EVT-01). */
enum EventType { GENERAL, WORKSHOP, COMPETITION }

/** A general event (lecture, social, sports day...). The only type that may recur (BR-11). */
class GeneralEvent extends Event {
    @Override public EventType type() { return EventType.GENERAL; }
}

/** Event lifecycle state: the event is taking place. */
final class OngoingState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.ONGOING; }
    @Override public void complete(Event e) { e.changeState(new CompletedState()); }
}

/** Event lifecycle state: registration is possible. */
final class OpenState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.OPEN; }
    @Override public void close(Event e) { e.changeState(new ClosedState()); }
    @Override public void start(Event e) { e.changeState(new OngoingState()); }
    @Override public void cancel(Event e) { e.changeState(new CancelledState()); }
}

/** Event lifecycle state: waiting for Staff to approve or reject the proposal. */
final class PendingApprovalState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.PENDING_APPROVAL; }
    @Override public void approve(Event e) { e.changeState(new PublishedState()); }
    @Override public void reject(Event e) { e.changeState(new DraftState()); }
    @Override public void withdraw(Event e) { e.changeState(new DraftState()); }
}

/** Event lifecycle state: visible, waiting for the registration opening time. */
final class PublishedState extends AbstractEventState {
    @Override public EventStatus status() { return EventStatus.PUBLISHED; }
    @Override public void open(Event e) { e.changeState(new OpenState()); }
    @Override public void cancel(Event e) { e.changeState(new CancelledState()); }
}

/** Expands a recurrence (daily, weekly on chosen weekdays, monthly on the same day) into time slots (FR-REC-01, BR-11). */
final class RecurrenceRule {
    public enum Frequency { DAILY, WEEKLY, MONTHLY }
    public static final int MAX_OCCURRENCES = 52;

    private final Frequency frequency;
    private final int interval;
    private final Set<DayOfWeek> weekdays;
    private final LocalDate until;
    private final Integer count;

    public RecurrenceRule(Frequency frequency, int interval, Set<DayOfWeek> weekdays, LocalDate until, Integer count) {
        if (interval < 1) throw new ValidationException("error.recurrence.interval");
        if (until == null && count == null) throw new ValidationException("error.recurrence.end");
        if (count != null && (count < 1 || count > MAX_OCCURRENCES)) throw new ValidationException("error.recurrence.count", MAX_OCCURRENCES);
        this.frequency = frequency;
        this.interval = interval;
        this.weekdays = weekdays == null || weekdays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekdays);
        this.until = until;
        this.count = count;
    }

    public Frequency getFrequency() { return frequency; }
    public int getInterval() { return interval; }
    public Set<DayOfWeek> getWeekdays() { return weekdays; }
    public LocalDate getUntil() { return until; }
    public Integer getCount() { return count; }

    /** Expands the rule starting with the first slot; the first slot is always occurrence 1. Times are kept in the zone. */
    public List<TimeSlot> expand(TimeSlot first, ZoneId zone) {
        ZonedDateTime start = first.start().atZone(zone);
        Duration length = Duration.between(first.start(), first.end());
        LocalDate firstDate = start.toLocalDate();
        List<LocalDate> dates = new ArrayList<>();
        int limit = count != null ? count : Integer.MAX_VALUE;
        switch (frequency) {
            case DAILY -> {
                for (int k = 0; dates.size() < limit; k++) {
                    LocalDate d = firstDate.plusDays((long) k * interval);
                    if (until != null && d.isAfter(until)) break;
                    dates.add(d);
                    if (dates.size() > MAX_OCCURRENCES) break;
                }
            }
            case WEEKLY -> {
                Set<DayOfWeek> days = weekdays.isEmpty() ? EnumSet.of(firstDate.getDayOfWeek()) : weekdays;
                LocalDate weekStart = firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                outer:
                for (int w = 0; ; w++) {
                    LocalDate ws = weekStart.plusWeeks((long) w * interval);
                    for (DayOfWeek dow : DayOfWeek.values()) {
                        if (!days.contains(dow)) continue;
                        LocalDate d = ws.plusDays(dow.getValue() - 1);
                        if (d.isBefore(firstDate)) continue;
                        if (until != null && d.isAfter(until)) break outer;
                        dates.add(d);
                        if (dates.size() >= limit || dates.size() > MAX_OCCURRENCES) break outer;
                    }
                    if (until == null && dates.size() >= limit) break;
                }
            }
            case MONTHLY -> {
                int dom = firstDate.getDayOfMonth();
                for (int m = 0; dates.size() < limit && m < 600; m++) {
                    LocalDate base = firstDate.withDayOfMonth(1).plusMonths((long) m * interval);
                    if (dom > base.lengthOfMonth()) continue;
                    LocalDate d = base.withDayOfMonth(dom);
                    if (until != null && d.isAfter(until)) break;
                    dates.add(d);
                    if (dates.size() > MAX_OCCURRENCES) break;
                }
            }
        }
        if (dates.size() > MAX_OCCURRENCES) throw new ValidationException("error.recurrence.count", MAX_OCCURRENCES);
        List<TimeSlot> slots = new ArrayList<>();
        for (LocalDate d : dates) {
            ZonedDateTime s = ZonedDateTime.of(d, start.toLocalTime(), zone);
            slots.add(new TimeSlot(s.toInstant(), s.toInstant().plus(length)));
        }
        return slots;
    }
}

/** Input data for one workshop session. {@code id} is null for a new session; a null venue means the workshop venue. */
record SessionData(Long id, LocalizedText topic, Instant start, Instant end, Long venueId) {}

/**
 * A workshop: the composite of its {@link WorkshopSession}s (FR-WSH-01). Start and end are derived from the first
 * and last session (FR-WSH-03); one registration covers all sessions (FR-WSH-05).
 */
class Workshop extends Event {
    private final List<WorkshopSession> sessions = new ArrayList<>();

    @Override public EventType type() { return EventType.WORKSHOP; }

    public List<WorkshopSession> getSessions() { return sessions; }

    /** Replaces the sessions, re-numbers them chronologically and derives start and end. */
    public void setSessions(List<WorkshopSession> list) {
        sessions.clear();
        sessions.addAll(list);
        sessions.sort(Comparator.comparing(s -> s.getSlot().start()));
        int i = 1;
        for (WorkshopSession s : sessions) s.setSeq(i++);
        recomputeBounds();
    }

    /** Derives start and end from the first and last session. */
    public void recomputeBounds() {
        if (sessions.isEmpty()) { setStartAt(null); setEndAt(null); return; }
        setStartAt(sessions.get(0).getSlot().start());
        setEndAt(sessions.stream().map(s -> s.getSlot().end()).max(Comparator.naturalOrder()).orElseThrow());
    }

    @Override public List<TimeSlot> getTimeSlots() {
        List<TimeSlot> slots = new ArrayList<>();
        for (WorkshopSession s : sessions) slots.addAll(s.getTimeSlots());
        return slots;
    }

    @Override public void applyData(EventData d) {
        super.applyData(d);
        if (d.sessions != null) {
            List<WorkshopSession> list = new ArrayList<>();
            for (SessionData sd : d.sessions) {
                WorkshopSession s = new WorkshopSession(sd.topic(), new TimeSlot(sd.start(), sd.end()), sd.venueId());
                if (sd.id() != null) s.setId(sd.id());
                list.add(s);
            }
            setSessions(list);
        }
    }

    /** Sessions must exist and must not overlap (FR-WSH-03). */
    @Override public void validateBasics() {
        super.validateBasics();
        for (int i = 1; i < sessions.size(); i++)
            if (sessions.get(i).getSlot().overlaps(sessions.get(i - 1).getSlot()))
                throw new ValidationException("error.workshop.overlap");
    }

    @Override public void validateForPublish(java.time.Instant now, int venueCapacity) {
        if (sessions.isEmpty()) throw new ValidationException("error.workshop.noSessions");
        super.validateForPublish(now, venueCapacity);
    }
}

/** One meeting of a workshop: the leaf of the Composite pattern (FR-WSH-01). */
class WorkshopSession implements Schedulable {
    private long id;
    private long eventId;
    private int seq;
    private LocalizedText topic = LocalizedText.EMPTY;
    private TimeSlot slot;
    private Long venueId;

    public WorkshopSession(LocalizedText topic, TimeSlot slot, Long venueId) {
        this.topic = topic; this.slot = slot; this.venueId = venueId;
    }
    @Override public List<TimeSlot> getTimeSlots() { return List.of(slot); }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long id) { this.eventId = id; }
    public int getSeq() { return seq; }
    public void setSeq(int s) { this.seq = s; }
    public LocalizedText getTopic() { return topic; }
    public TimeSlot getSlot() { return slot; }
    /** Venue of this session; null means the workshop's venue. */
    public Long getVenueId() { return venueId; }
}

// ======== domain.registration ========

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

// ======== domain.club ========

/** A student organization with members and officers (glossary). */
class Club {
    private long id;
    private LocalizedText name = LocalizedText.EMPTY;
    private LocalizedText description = LocalizedText.EMPTY;
    private Long categoryId;
    private boolean active = true;
    private int version;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public LocalizedText getName() { return name; }
    public void setName(LocalizedText n) { this.name = n; }
    public LocalizedText getDescription() { return description; }
    public void setDescription(LocalizedText d) { this.description = d; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long c) { this.categoryId = c; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
}

/** A user's position in a club: role (member or officer) and status (pending, active, rejected, ended). */
class ClubMembership {
    private long id;
    private long clubId;
    private long userId;
    private MembershipRole role = MembershipRole.MEMBER;
    private MembershipStatus status = MembershipStatus.PENDING;
    private Instant requestedAt;
    private Instant decidedAt;
    private Instant joinedAt;
    private String reason;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getClubId() { return clubId; }
    public void setClubId(long c) { this.clubId = c; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public MembershipRole getRole() { return role; }
    public void setRole(MembershipRole r) { this.role = r; }
    public MembershipStatus getStatus() { return status; }
    public void setStatus(MembershipStatus s) { this.status = s; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant t) { this.requestedAt = t; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant t) { this.decidedAt = t; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant t) { this.joinedAt = t; }
    public String getReason() { return reason; }
    public void setReason(String r) { this.reason = r; }
}

/** A membership with the person's identity, for club screens. */
record MemberRow(ClubMembership membership, String universityId, LocalizedText name) {}

/** Role inside one club. Officer is a position, not an account type (BR-01). */
enum MembershipRole { MEMBER, OFFICER }

/** Lifecycle of a club membership. */
enum MembershipStatus { PENDING, ACTIVE, REJECTED, ENDED }

// ======== domain.competition ========

/** A placement (1st-3rd, ties allowed) with an optional score for one participant (FR-CMP-02). */
class CompetitionResult {
    private long id;
    private long eventId;
    private long userId;
    private int placement;
    private BigDecimal score;
    private String universityId = "";
    private LocalizedText name = LocalizedText.EMPTY;
    public CompetitionResult() {}
    public CompetitionResult(long eventId, long userId, int placement, BigDecimal score) {
        this.eventId = eventId; this.userId = userId; this.placement = placement; this.score = score;
    }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long e) { this.eventId = e; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public int getPlacement() { return placement; }
    public void setPlacement(int p) { this.placement = p; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal s) { this.score = s; }
    public String getUniversityId() { return universityId; }
    public void setUniversityId(String u) { this.universityId = u; }
    public LocalizedText getName() { return name; }
    public void setName(LocalizedText n) { this.name = n; }
}

// ======== domain.social ========

/** A discussion comment; one level of replies; moderators remove by soft deletion (FR-DSC-02, FR-DSC-04). */
class Comment {
    private long id;
    private long eventId;
    private long userId;
    private Long parentId;
    private String body;
    private Instant createdAt;
    private boolean edited;
    private boolean removed;
    private String removedReason;
    private LocalizedText authorName = LocalizedText.EMPTY;
    private AccountType authorType = AccountType.STUDENT;
    private boolean authorIsOfficer;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long e) { this.eventId = e; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long p) { this.parentId = p; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean e) { this.edited = e; }
    public boolean isRemoved() { return removed; }
    public void setRemoved(boolean r) { this.removed = r; }
    public String getRemovedReason() { return removedReason; }
    public void setRemovedReason(String r) { this.removedReason = r; }
    public LocalizedText getAuthorName() { return authorName; }
    public void setAuthorName(LocalizedText n) { this.authorName = n; }
    public AccountType getAuthorType() { return authorType; }
    public void setAuthorType(AccountType t) { this.authorType = t; }
    public boolean isAuthorIsOfficer() { return authorIsOfficer; }
    public void setAuthorIsOfficer(boolean b) { this.authorIsOfficer = b; }
}

/** A rating (1-5) with an optional comment; one per student and event (BR-09). */
class Feedback {
    private long id;
    private long eventId;
    private long userId;
    private int rating;
    private String comment;
    private Instant createdAt;
    private Instant updatedAt;
    private LocalizedText authorName = LocalizedText.EMPTY;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long e) { this.eventId = e; }
    public long getUserId() { return userId; }
    public void setUserId(long u) { this.userId = u; }
    public int getRating() { return rating; }
    public void setRating(int r) { this.rating = r; }
    public String getComment() { return comment; }
    public void setComment(String c) { this.comment = c; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public LocalizedText getAuthorName() { return authorName; }
    public void setAuthorName(LocalizedText n) { this.authorName = n; }
}

/** Aggregated feedback: average, count, distribution (index 1..5) and, depending on the viewer, the comments (FR-FBK-03). */
record FeedbackSummary(double average, int count, int[] distribution, List<Feedback> comments, boolean namesVisible) {}

// ======== domain.notification ========

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

// ======== domain.events ========

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

/** NT-15: announcement by an organizer to registrants or club members. */
record AnnouncementSent(String link, LocalizedText subject, LocalizedText message, List<Long> recipients) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-15", "notif.announcement", link, Params.text(subject), Params.text(message)));
        return l;
    }
}

/** An organizer undid a check-in (audit, FR-CHK-05). */
record CheckInChanged(long actorId, long registrationId, String action) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, action, "registration:" + registrationId, null); }
}

/** A moderator removed a comment (audit, FR-DSC-04). */
record CommentRemoved(long actorId, long commentId, long eventId, String reason) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, "COMMENT_REMOVED", "comment:" + commentId + " event:" + eventId, reason); }
}

/** NT-16: someone replied to a comment. */
record CommentReplied(long parentAuthorId, long eventId, LocalizedText title, LocalizedText replier) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(parentAuthorId, "NT-16", "notif.comment.reply", "event:" + eventId, Params.text(replier), Params.text(title)));
    }
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

/** NT-06: event cancelled; sent to all confirmed and waitlisted registrants. Audited. */
record EventCancelled(long actorId, long eventId, LocalizedText title, List<Long> recipients, String reason) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-06", "notif.event.cancelled", "event:" + eventId, Params.text(title), reason));
        return l;
    }
    @Override public AuditSpec audit() { return new AuditSpec(actorId, "EVENT_CANCEL", "event:" + eventId, reason); }
}

/** NT-07: time, venue or sessions changed. */
record EventChanged(long eventId, LocalizedText title, List<Long> recipients) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-07", "notif.event.changed", "event:" + eventId, Params.text(title)));
        return l;
    }
}

/** NT-08: reminder 24 hours before the start (each session for workshops). */
record EventReminder(long eventId, LocalizedText title, Instant startsAt, List<Long> recipients) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-08", "notif.event.reminder", "event:" + eventId, Params.text(title), Params.instant(startsAt)));
        return l;
    }
}

/** NT-14: feedback requested from students who attended a completed event. */
record FeedbackRequested(long eventId, LocalizedText title, List<Long> recipients) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-14", "notif.feedback.requested", "event:" + eventId, Params.text(title)));
        return l;
    }
}

/** NT-11: a join request was approved or rejected; sent to the student. */
record JoinRequestDecided(long clubId, LocalizedText club, long studentId, boolean approved, String reason) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(approved
            ? spec(studentId, "NT-11", "notif.join.approved", "club:" + clubId, Params.text(club))
            : spec(studentId, "NT-11", "notif.join.rejected", "club:" + clubId, Params.text(club), reason == null ? "" : reason));
    }
}

/** NT-11: a student asked to join a club; sent to the officers. */
record JoinRequestSubmitted(long clubId, LocalizedText club, LocalizedText student, List<Long> officerIds) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : officerIds) l.add(spec(u, "NT-11", "notif.join.submitted", "club:" + clubId, Params.text(student), Params.text(club)));
        return l;
    }
}

/** A login attempt failed (FR-USR-08, NFR-SEC-07). */
record LoginFailed(String universityId, int attempts) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(null, "LOGIN_FAILED", universityId, "attempts=" + attempts); }
}

/** NT-12: officer appointed or removed; audited. */
record OfficerChanged(long actorId, long clubId, LocalizedText club, long userId, boolean appointed) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-12", appointed ? "notif.officer.appointed" : "notif.officer.removed", "club:" + clubId, Params.text(club)));
    }
    @Override public AuditSpec audit() { return new AuditSpec(actorId, appointed ? "OFFICER_APPOINT" : "OFFICER_REMOVE", "club:" + clubId + " user:" + userId, null); }
}

/** NT-04: a seat became free but promotion was skipped because of a clash (FR-WTL-03). */
record PromotionSkipped(long userId, long eventId, LocalizedText title) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-04", "notif.waitlist.skipped", "event:" + eventId, Params.text(title)));
    }
}

/** NT-10: proposal approved or rejected with reason; audited. */
record ProposalDecided(long actorId, long eventId, LocalizedText title, long officerId, boolean approved, String reason) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(approved
            ? spec(officerId, "NT-10", "notif.proposal.approved", "event:" + eventId, Params.text(title))
            : spec(officerId, "NT-10", "notif.proposal.rejected", "event:" + eventId, Params.text(title), reason));
    }
    @Override public AuditSpec audit() { return new AuditSpec(actorId, approved ? "EVENT_APPROVE" : "EVENT_REJECT", "event:" + eventId, reason); }
}

/** NT-09: an officer submitted a proposal; sent to all Staff. */
record ProposalSubmitted(long eventId, LocalizedText title, LocalizedText club, List<Long> staffIds) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : staffIds) l.add(spec(u, "NT-09", "notif.proposal.submitted", "event:" + eventId, Params.text(title), Params.text(club)));
        return l;
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

/** NT-13: competition results published or corrected (corrections are audited, FR-CMP-05). */
record ResultsPublished(long actorId, long eventId, LocalizedText title, List<Long> recipients, boolean correction) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-13", correction ? "notif.results.corrected" : "notif.results.published", "event:" + eventId, Params.text(title)));
        return l;
    }
    @Override public AuditSpec audit() { return correction ? new AuditSpec(actorId, "RESULTS_CORRECTED", "event:" + eventId, null) : null; }
}

/** A system setting was changed by an administrator (audit). */
record SettingChanged(long actorId, String key, String value) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(actorId, "SETTING_CHANGE", key, value); }
}

/** A user logged in (audit). */
record UserLoggedIn(long userId, String universityId) implements DomainEvent {
    @Override public AuditSpec audit() { return new AuditSpec(userId, "LOGIN", universityId, null); }
}

/** NT-03: promoted from the waitlist. */
record WaitlistPromoted(long userId, long eventId, LocalizedText title) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-03", "notif.waitlist.promoted", "event:" + eventId, Params.text(title)));
    }
}

// ======== domain.recommendation ========

/** R2: the event is hosted by a club the student belongs to. */
class ClubRule implements RecommendationRule {
    @Override public RuleScore score(RecommendationContext ctx, Event e) {
        if (e.getHostClubId() != null && ctx.clubIds().contains(e.getHostClubId()))
            return new RuleScore(1.0, new Reason("reason.club", ctx.clubNames().get(e.getHostClubId())));
        return RuleScore.ZERO;
    }
    @Override public String weightKey() { return "weight.club"; }
}

/** R3: the event's category matches events the student attended before. */
class HistoryRule implements RecommendationRule {
    @Override public RuleScore score(RecommendationContext ctx, Event e) {
        if (e.getCategoryId() != null && ctx.attendedCategoryIds().contains(e.getCategoryId()))
            return new RuleScore(1.0, new Reason("reason.history", ctx.categoryNames().get(e.getCategoryId())));
        return RuleScore.ZERO;
    }
    @Override public String weightKey() { return "weight.history"; }
}

/** R1: overlap of the event's tags with the student's interests. */
class InterestRule implements RecommendationRule {
    @Override public RuleScore score(RecommendationContext ctx, Event e) {
        for (Long tag : e.getTagIds())
            if (ctx.interestTagIds().contains(tag))
                return new RuleScore(1.0, new Reason("reason.interest", ctx.tagNames().get(tag)));
        return RuleScore.ZERO;
    }
    @Override public String weightKey() { return "weight.interest"; }
}

/** Why an event was recommended: a bundle key and an optional name argument (FR-RCM-04). */
record Reason(String key, LocalizedText arg) {}

/** One recommended event with its total score and the reason of the strongest rule. */
record Recommendation(Event event, double score, Reason reason) {}

/** Everything a rule may look at about one student (read-only snapshot supplied by the engine). */
record RecommendationContext(long studentId, Set<Long> interestTagIds, Set<Long> clubIds,
                                    Set<Long> attendedCategoryIds, Map<Long, LocalizedText> tagNames,
                                    Map<Long, LocalizedText> categoryNames, Map<Long, LocalizedText> clubNames, Instant now) {
    /** True when the student has no interests, clubs or history (FR-RCM-05). */
    public boolean isEmptyProfile() { return interestTagIds.isEmpty() && clubIds.isEmpty() && attendedCategoryIds.isEmpty(); }
}

/**
 * Strategy: one independent scoring rule. A new rule is a new class plus a line in recommendation.properties,
 * with no change to existing rules (FR-RCM-06). Implementations need a public no-argument constructor.
 */
interface RecommendationRule {
    /** Pure function returning a score in 0..1 and a reason. */
    RuleScore score(RecommendationContext ctx, Event event);
    /** Key of the weight in recommendation.properties. */
    String weightKey();
}

/** Result of one rule: a value in 0..1 and the reason shown to the student. */
record RuleScore(double value, Reason reason) {
    public static final RuleScore ZERO = new RuleScore(0, null);
}

/** R4: the event starts within the next 14 days; sooner scores higher. */
class SoonRule implements RecommendationRule {
    private static final Duration WINDOW = Duration.ofDays(14);
    @Override public RuleScore score(RecommendationContext ctx, Event e) {
        Duration until = Duration.between(ctx.now(), e.getStartAt());
        if (until.isNegative() || until.compareTo(WINDOW) > 0) return RuleScore.ZERO;
        double v = 1.0 - (double) until.toHours() / WINDOW.toHours();
        return new RuleScore(Math.max(0.1, v), new Reason("reason.soon", null));
    }
    @Override public String weightKey() { return "weight.soon"; }
}

// ======== domain.search ========

/** An event with seat figures and rating, for lists and cards (FR-EVT-08, FR-FBK-04). */
record EventCard(Event event, int confirmed, int waitlisted, double averageRating, int ratingCount) {
    /** Seats still free (never negative). */
    public int seatsLeft() { return Math.max(0, event.getCapacity() - confirmed); }
}

/** Parameter object for event search and filtering; keeps the SearchService signature stable (FR-SRC-01..04). */
class SearchCriteria {
    public enum Sort { DATE, TITLE, POPULARITY }
    /** Which events the caller may see: guests and users see public states, managers see all states. */
    public enum Visibility { PUBLIC, LOGGED_IN, MANAGEMENT }

    public static final int PAGE_SIZE = 20;

    public String keyword = "";
    public EventType type;
    public Long categoryId;
    public Instant from;
    public Instant to;
    public Long venueId;
    public Long hostClubId;
    public boolean openOnly;
    public boolean seatsAvailableOnly;
    public Sort sort = Sort.DATE;
    public int page;
    public int pageSize = PAGE_SIZE;
    /** Include events that already ended (history). */
    public boolean includePast;
    /** Optional state filter for management lists (FR-LCY-10). */
    public Set<EventStatus> statuses = EnumSet.noneOf(EventStatus.class);
    public Visibility visibility = Visibility.LOGGED_IN;
}

// ======== domain.repo ========

/** Persistence contract for the audit log. */
interface AuditRepository {
    void save(Long actorId, String action, String target, String details, Instant at);
    /** Filters by optional date range, actor text (id or name) and action. */
    List<AuditEntry> search(Instant from, Instant to, String actorText, String action, int limit);
}

/** Persistence contract for categories, tags and system settings. */
interface CatalogRepository {
    /** A category or tag. */
    record Item(long id, LocalizedText name) {}
    List<Item> findCategories();
    List<Item> findTags();
    Item saveCategory(LocalizedText name);
    Item saveTag(LocalizedText name);
    Map<Long, LocalizedText> categoryNames();
    Map<Long, LocalizedText> tagNames();
    Optional<String> getSetting(String key);
    void setSetting(String key, String value);
    int schemaVersion();
}

/** Persistence contract for clubs and memberships. */
interface ClubRepository {
    Optional<Club> findById(long id);
    Club lockById(long id);
    List<Club> findAll(boolean activeOnly);
    Club save(Club c);
    Map<Long, LocalizedText> findNames(java.util.Collection<Long> ids);
    /** The user's PENDING or ACTIVE membership in the club, if any. */
    Optional<ClubMembership> findOpenMembership(long clubId, long userId);
    Optional<ClubMembership> findMembership(long membershipId);
    ClubMembership saveMembership(ClubMembership m);
    /** Active members and officers. */
    List<MemberRow> findMembers(long clubId, String search);
    List<MemberRow> findPending(long clubId);
    List<MemberRow> findOfficers(long clubId);
    int countOfficers(long clubId);
    int countMembers(long clubId);
    boolean isActiveOfficer(long userId, long clubId);
    List<Long> findOfficerClubIds(long userId);
    /** Clubs where the user is an active member or officer. */
    List<Long> findActiveClubIds(long userId);
    boolean holdsOfficerPosition(long userId);
    List<Long> findOfficerUserIds(long clubId);
    List<Long> findMemberUserIds(long clubId);
}

/** Persistence contract for events, workshop sessions, series, history and posters. */
interface EventRepository {
    /** A recorded lifecycle transition (FR-LCY-09). */
    record StateChange(EventStatus from, EventStatus to, Long userId, String note, Instant at) {}
    /** A poster image (FR-EVT-02). */
    record Poster(String contentType, byte[] data) {}

    Optional<Event> findById(long id);
    /** Loads and locks the event row (SELECT ... FOR UPDATE). Requires a transaction. Throws NotFoundException. */
    Event lockById(long id);
    /** Inserts (id 0) or updates with an optimistic version check; throws StaleDataException if stale. Returns the saved event. */
    Event save(Event e);
    void delete(long id);
    List<Event> findByIds(Collection<Long> ids);
    /** Bookings that block the venue within the slot, optionally ignoring one event (FR-VEN-03). */
    List<Booking> findBlocking(long venueId, TimeSlot slot, Long ignoreEventId);
    /** Non-cancelled events in which the student holds an active registration and that end after the instant. */
    List<Event> findActiveRegisteredBy(long userId, Instant endsAfter);
    List<Event> findBySeries(long seriesId);
    long saveSeries(EventSeries s);
    void recordHistory(long eventId, EventStatus from, EventStatus to, Long userId, String note, Instant at);
    List<StateChange> findHistory(long eventId);
    void savePoster(long eventId, String contentType, byte[] data);
    void deletePoster(long eventId);
    Optional<Poster> findPoster(long eventId);
    /** Upcoming Open events for recommendations. */
    List<Event> findUpcomingOpen(Instant now, int limit);
    /** Events of a club that are public and not yet completed/cancelled, plus past ones, newest first. */
    List<Event> findPublicByClub(long clubId);
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

/** Persistence contract for competition results. */
interface ResultRepository {
    List<CompetitionResult> findByEvent(long eventId);
    /** Replaces the whole result list of the event. */
    void replaceAll(long eventId, List<CompetitionResult> results);
    void setPublished(long eventId, boolean published);
    boolean isPublished(long eventId);
    /** Placements of a student across competitions (FR-CMP-07): rows of {event title, placement}. */
    List<CompetitionResult> findByUser(long userId);
}

/** Compare-and-set state changes and reminder bookkeeping for the scheduler; every call is idempotent (NFR-REL-05). */
interface SchedulerRepository {
    /** A reminder to send: event, session id (0 when none) and start time. */
    record DueReminder(long eventId, long sessionId, Instant startsAt) {}
    /** Published events whose registration opening time has come become Open; returns affected event ids. */
    List<Long> openPublished(Instant now);
    /** Open events past their registration deadline become Closed. */
    List<Long> closeOpen(Instant now);
    /** Open or Closed events whose start has come become Ongoing. */
    List<Long> startEvents(Instant now);
    /** Ongoing events whose end has come become Completed. */
    List<Long> completeEvents(Instant now);
    List<DueReminder> findDueReminders(Instant from, Instant to);
    /** INSERT ... ON CONFLICT DO NOTHING; true if this call inserted the row. */
    boolean tryLogReminder(long eventId, long sessionId, Instant at);
    /** Anonymizes feedback and registration free text older than the retention date (NFR-PRV-04); returns rows changed. */
    int applyRetention(Instant before);
}

/** Persistence contract for event search; builds parameterized SQL only (NFR-SEC-04). */
interface SearchRepository {
    Page<EventCard> search(SearchCriteria criteria, Instant now);
}

/** Persistence contract for feedback and discussion comments. */
interface SocialRepository {
    Optional<Feedback> findFeedback(long eventId, long userId);
    Feedback saveFeedback(Feedback f);
    List<Feedback> findFeedbackByEvent(long eventId);
    Comment saveComment(Comment c);
    Optional<Comment> findComment(long id);
    /** Chronological, with author names, types and officer flags. */
    List<Comment> findComments(long eventId);
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
