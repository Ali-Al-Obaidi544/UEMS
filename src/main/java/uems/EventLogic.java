package uems;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static uems.DomainEvent.spec;
import static uems.Permission.*;

// MODULE 2 - EVENTS AND LIFECYCLE (logic)
// Contains the domain classes for events (state machine, workshop composite, recurrence), event/catalog/scheduler repositories, and the services for drafts, publishing, proposals, recurring series, cancellation, categories/tags and the lifecycle scheduler.
// Layers in this file, in order: INFRASTRUCTURE (module 1 only), DOMAIN, PERSISTENCE, SERVICES.

// ========== DOMAIN ==========

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

// ========== PERSISTENCE ==========

/** JDBC implementation of {@link CatalogRepository}. */
class JdbcCatalogRepository implements CatalogRepository {
    private final Jdbc db;
    public JdbcCatalogRepository(Jdbc db) { this.db = db; }

    private List<Item> items(String sql) { return db.list(sql, rs -> new Item(rs.getLong(1), new LocalizedText(rs.getString(2), rs.getString(3)))); }
    @Override public List<Item> findCategories() { return items("SELECT category_id, name_en, name_ar FROM category ORDER BY name_en"); }
    @Override public List<Item> findTags() { return items("SELECT tag_id, name_en, name_ar FROM tag ORDER BY name_en"); }
    @Override public Item saveCategory(LocalizedText n) {
        return new Item(db.insert("INSERT INTO category (name_en, name_ar) VALUES (?,?) RETURNING category_id", n.en(), n.ar().isEmpty() ? null : n.ar()), n);
    }
    @Override public Item saveTag(LocalizedText n) {
        return new Item(db.insert("INSERT INTO tag (name_en, name_ar) VALUES (?,?) RETURNING tag_id", n.en(), n.ar().isEmpty() ? null : n.ar()), n);
    }
    @Override public Map<Long, LocalizedText> categoryNames() { Map<Long, LocalizedText> m = new HashMap<>(); for (Item i : findCategories()) m.put(i.id(), i.name()); return m; }
    @Override public Map<Long, LocalizedText> tagNames() { Map<Long, LocalizedText> m = new HashMap<>(); for (Item i : findTags()) m.put(i.id(), i.name()); return m; }
    @Override public Optional<String> getSetting(String key) { return db.one("SELECT setting_value FROM app_setting WHERE setting_key=?", rs -> rs.getString(1), key); }
    @Override public void setSetting(String key, String value) {
        db.exec("INSERT INTO app_setting (setting_key, setting_value) VALUES (?,?) ON CONFLICT (setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value", key, value);
    }
    @Override public int schemaVersion() { return db.list("SELECT version FROM schema_version", rs -> rs.getInt(1)).stream().findFirst().orElse(0); }
}

/** JDBC implementation of {@link EventRepository}. The event hierarchy is one table with a type discriminator. */
class JdbcEventRepository implements EventRepository {
    static final String COLS = "e.event_id, e.event_type, e.title_en, e.title_ar, e.description_en, e.description_ar, e.category_id, "
            + "e.venue_id, e.host_club_id, e.capacity, e.start_at, e.end_at, e.reg_opens_at, e.reg_deadline_at, e.cancel_deadline_at, "
            + "e.state, e.series_id, e.series_index, e.created_by, e.proposal, e.rejection_reason, e.cancel_reason, e.results_published, e.version";
    private static final String BLOCKING = "('PUBLISHED','OPEN','CLOSED','ONGOING')";
    private final Jdbc db;
    public JdbcEventRepository(Jdbc db) { this.db = db; }

    /** Maps the current row to the right Event subclass (tags and sessions are loaded by {@link #hydrate}). */
    static Event mapRow(ResultSet rs) throws SQLException {
        Event e = switch (EventType.valueOf(rs.getString("event_type"))) {
            case GENERAL -> new GeneralEvent();
            case WORKSHOP -> new Workshop();
            case COMPETITION -> { Competition c = new Competition(); c.setResultsPublished(rs.getBoolean("results_published")); yield c; }
        };
        e.setId(rs.getLong("event_id"));
        e.setTitle(new LocalizedText(rs.getString("title_en"), rs.getString("title_ar")));
        e.setDescription(new LocalizedText(rs.getString("description_en"), rs.getString("description_ar")));
        e.setCategoryId(Jdbc.longOrNull(rs, "category_id"));
        e.setVenueId(Jdbc.longOrNull(rs, "venue_id"));
        e.setHostClubId(Jdbc.longOrNull(rs, "host_club_id"));
        e.setCapacity(rs.getInt("capacity"));
        e.setStartAt(Jdbc.instant(rs, "start_at"));
        e.setEndAt(Jdbc.instant(rs, "end_at"));
        e.setRegOpensAt(Jdbc.instant(rs, "reg_opens_at"));
        e.setRegDeadlineAt(Jdbc.instant(rs, "reg_deadline_at"));
        e.setCancelDeadlineAt(Jdbc.instant(rs, "cancel_deadline_at"));
        e.restoreState(EventStatus.valueOf(rs.getString("state")));
        e.setSeriesId(Jdbc.longOrNull(rs, "series_id"));
        e.setSeriesIndex(Jdbc.intOrNull(rs, "series_index"));
        e.setCreatedBy(rs.getLong("created_by"));
        e.setProposal(rs.getBoolean("proposal"));
        e.setRejectionReason(rs.getString("rejection_reason"));
        e.setCancelReason(rs.getString("cancel_reason"));
        e.setVersion(rs.getInt("version"));
        return e;
    }

    /** Loads tags and workshop sessions for the events (two queries regardless of the number of events). */
    public List<Event> hydrate(List<Event> events) {
        if (events.isEmpty()) return events;
        List<Long> ids = events.stream().map(Event::getId).collect(Collectors.toList());
        Map<Long, Event> byId = new HashMap<>();
        for (Event e : events) { byId.put(e.getId(), e); e.setTagIds(new HashSet<>()); }
        db.list("SELECT event_id, tag_id FROM event_tag WHERE event_id = ANY(?)", rs -> {
            byId.get(rs.getLong(1)).getTagIds().add(rs.getLong(2)); return null; }, ids);
        Map<Long, List<WorkshopSession>> sessions = new HashMap<>();
        db.list("SELECT session_id, event_id, seq, topic_en, topic_ar, start_at, end_at, venue_id FROM workshop_session WHERE event_id = ANY(?) ORDER BY start_at",
                rs -> {
                    WorkshopSession s = new WorkshopSession(new LocalizedText(rs.getString("topic_en"), rs.getString("topic_ar")),
                            new TimeSlot(Jdbc.instant(rs, "start_at"), Jdbc.instant(rs, "end_at")), Jdbc.longOrNull(rs, "venue_id"));
                    s.setId(rs.getLong("session_id")); s.setEventId(rs.getLong("event_id")); s.setSeq(rs.getInt("seq"));
                    sessions.computeIfAbsent(s.getEventId(), k -> new ArrayList<>()).add(s);
                    return null;
                }, ids);
        for (Event e : events) if (e instanceof Workshop w) {
            Instant s = w.getStartAt(), en = w.getEndAt();
            w.setSessions(sessions.getOrDefault(w.getId(), List.of()));
            if (s != null) { w.setStartAt(s); w.setEndAt(en); }
        }
        return events;
    }

    @Override public Optional<Event> findById(long id) {
        List<Event> l = hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.event_id=?", JdbcEventRepository::mapRow, id));
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override public Event lockById(long id) {
        List<Event> l = hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.event_id=?" + db.dialect().forUpdate(), JdbcEventRepository::mapRow, id));
        if (l.isEmpty()) throw new NotFoundException("error.event.notFound");
        return l.get(0);
    }

    @Override public List<Event> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.event_id = ANY(?) ORDER BY e.start_at", JdbcEventRepository::mapRow, ids));
    }

    @Override public Event save(Event e) {
        if (e instanceof Workshop w) w.recomputeBounds();
        String searchText = buildSearchText(e);
        Object[] v = {e.type(), nn(e.getTitle().en()), nn(e.getTitle().ar()), nn(e.getDescription().en()), nn(e.getDescription().ar()),
                e.getCategoryId(), e.getVenueId(), e.getHostClubId(), e.getCapacity(), e.getStartAt(), e.getEndAt(), e.getRegOpensAt(),
                e.getRegDeadlineAt(), e.getCancelDeadlineAt(), e.status(), e.getSeriesId(), e.getSeriesIndex(), e.getCreatedBy(),
                e.isProposal(), e.getRejectionReason(), e.getCancelReason(), searchText};
        if (e.getId() == 0) {
            e.setId(db.insert("INSERT INTO event (event_type, title_en, title_ar, description_en, description_ar, category_id, venue_id, host_club_id, "
                    + "capacity, start_at, end_at, reg_opens_at, reg_deadline_at, cancel_deadline_at, state, series_id, series_index, created_by, "
                    + "proposal, rejection_reason, cancel_reason, search_text) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING event_id", v));
            e.setVersion(0);
        } else {
            Object[] a = Arrays.copyOf(v, v.length + 2);
            a[v.length] = e.getId(); a[v.length + 1] = e.getVersion();
            int n = db.exec("UPDATE event SET event_type=?, title_en=?, title_ar=?, description_en=?, description_ar=?, category_id=?, venue_id=?, host_club_id=?, "
                    + "capacity=?, start_at=?, end_at=?, reg_opens_at=?, reg_deadline_at=?, cancel_deadline_at=?, state=?, series_id=?, series_index=?, created_by=?, "
                    + "proposal=?, rejection_reason=?, cancel_reason=?, search_text=?, version=version+1 WHERE event_id=? AND version=?", a);
            if (n == 0) throw new StaleDataException("error.stale");
            e.setVersion(e.getVersion() + 1);
        }
        db.exec("DELETE FROM event_tag WHERE event_id=?", e.getId());
        for (Long t : e.getTagIds()) db.exec("INSERT INTO event_tag (event_id, tag_id) VALUES (?,?)", e.getId(), t);
        if (e instanceof Workshop w) saveSessions(w);
        return e;
    }

    private void saveSessions(Workshop w) {
        Set<Long> keep = new HashSet<>();
        for (WorkshopSession s : w.getSessions()) {
            if (s.getId() != 0) {
                keep.add(s.getId());
                db.exec("UPDATE workshop_session SET seq=?, topic_en=?, topic_ar=?, start_at=?, end_at=?, venue_id=? WHERE session_id=? AND event_id=?",
                        s.getSeq(), nn(s.getTopic().en()), nn(s.getTopic().ar()), s.getSlot().start(), s.getSlot().end(), s.getVenueId(), s.getId(), w.getId());
            }
        }
        // delete removed sessions
        List<Long> existing = db.list("SELECT session_id FROM workshop_session WHERE event_id=?", rs -> rs.getLong(1), w.getId());
        for (Long id : existing) if (!keep.contains(id)) db.exec("DELETE FROM workshop_session WHERE session_id=?", id);
        for (WorkshopSession s : w.getSessions()) {
            if (s.getId() == 0) {
                s.setId(db.insert("INSERT INTO workshop_session (event_id, seq, topic_en, topic_ar, start_at, end_at, venue_id) VALUES (?,?,?,?,?,?,?) RETURNING session_id",
                        w.getId(), s.getSeq(), nn(s.getTopic().en()), nn(s.getTopic().ar()), s.getSlot().start(), s.getSlot().end(), s.getVenueId()));
                s.setEventId(w.getId());
            }
        }
    }

    private String buildSearchText(Event e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getTitle().en()).append(' ').append(e.getTitle().ar()).append(' ')
          .append(e.getDescription().en()).append(' ').append(e.getDescription().ar()).append(' ');
        if (e.getCategoryId() != null)
            db.list("SELECT name_en, COALESCE(name_ar,'') FROM category WHERE category_id=?", rs -> sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(' '), e.getCategoryId());
        if (!e.getTagIds().isEmpty())
            db.list("SELECT name_en, COALESCE(name_ar,'') FROM tag WHERE tag_id = ANY(?)", rs -> sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(' '), e.getTagIds());
        if (e instanceof Workshop w) for (WorkshopSession s : w.getSessions()) sb.append(s.getTopic().en()).append(' ').append(s.getTopic().ar()).append(' ');
        return ArabicNormalizer.normalize(sb.toString());
    }
    private static String nn(String s) { return s == null ? "" : s; }

    @Override public void delete(long id) { db.exec("DELETE FROM event WHERE event_id=?", id); }

    @Override public List<Booking> findBlocking(long venueId, TimeSlot slot, Long ignoreEventId) {
        String ignoreE = ignoreEventId == null ? "" : " AND e.event_id<>?";
        String sql = "SELECT e.event_id, e.title_en, e.title_ar, e.start_at AS s, e.end_at AS f FROM event e WHERE e.venue_id=? AND e.event_type<>'WORKSHOP' "
                + "AND e.state IN " + BLOCKING + " AND e.start_at<? AND e.end_at>?" + ignoreE
                + " UNION ALL SELECT e.event_id, e.title_en, e.title_ar, s.start_at, s.end_at FROM workshop_session s JOIN event e ON e.event_id=s.event_id "
                + "WHERE COALESCE(s.venue_id, e.venue_id)=? AND e.state IN " + BLOCKING + " AND s.start_at<? AND s.end_at>?" + ignoreE + " ORDER BY 4";
        List<Object> args = new ArrayList<>(List.of(venueId, slot.end(), slot.start()));
        if (ignoreEventId != null) args.add(ignoreEventId);
        args.addAll(List.of(venueId, slot.end(), slot.start()));
        if (ignoreEventId != null) args.add(ignoreEventId);
        return db.list(sql, rs -> new Booking(rs.getLong(1), new LocalizedText(rs.getString(2), rs.getString(3)),
                new TimeSlot(Jdbc.instant(rs, "s"), Jdbc.instant(rs, "f"))), args.toArray());
    }

    @Override public List<Event> findActiveRegisteredBy(long userId, Instant endsAfter) {
        return hydrate(db.list("SELECT " + COLS + " FROM event e JOIN registration r ON r.event_id=e.event_id WHERE r.user_id=? "
                + "AND r.status IN ('CONFIRMED','WAITLISTED','ATTENDED') AND e.state NOT IN ('CANCELLED','DRAFT') AND e.end_at>? ORDER BY e.start_at",
                JdbcEventRepository::mapRow, userId, endsAfter));
    }

    @Override public List<Event> findBySeries(long seriesId) {
        return hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.series_id=? ORDER BY e.series_index", JdbcEventRepository::mapRow, seriesId));
    }

    @Override public long saveSeries(EventSeries s) {
        RecurrenceRule r = s.getRule();
        String days = r.getWeekdays().stream().map(DayOfWeek::name).collect(Collectors.joining(","));
        LocalDate until = r.getUntil();
        long id = db.insert("INSERT INTO event_series (frequency, step, weekdays, until_date, max_count) VALUES (?,?,?,?,?) RETURNING series_id",
                r.getFrequency().name(), r.getInterval(), days, until, r.getCount());
        s.setId(id);
        return id;
    }

    @Override public void recordHistory(long eventId, EventStatus from, EventStatus to, Long userId, String note, Instant at) {
        db.exec("INSERT INTO event_state_history (event_id, from_state, to_state, changed_by, note, changed_at) VALUES (?,?,?,?,?,?)",
                eventId, from, to, userId, note, at);
    }

    @Override public List<StateChange> findHistory(long eventId) {
        return db.list("SELECT from_state, to_state, changed_by, note, changed_at FROM event_state_history WHERE event_id=? ORDER BY history_id",
                rs -> new StateChange(rs.getString(1) == null ? null : EventStatus.valueOf(rs.getString(1)), EventStatus.valueOf(rs.getString(2)),
                        Jdbc.longOrNull(rs, "changed_by"), rs.getString("note"), Jdbc.instant(rs, "changed_at")), eventId);
    }

    @Override public void savePoster(long eventId, String contentType, byte[] data) {
        db.exec("DELETE FROM event_poster WHERE event_id=?", eventId);
        db.exec("INSERT INTO event_poster (event_id, content_type, data) VALUES (?,?,?)", eventId, contentType, data);
    }
    @Override public void deletePoster(long eventId) { db.exec("DELETE FROM event_poster WHERE event_id=?", eventId); }
    @Override public Optional<Poster> findPoster(long eventId) {
        return db.one("SELECT content_type, data FROM event_poster WHERE event_id=?", rs -> new Poster(rs.getString(1), rs.getBytes(2)), eventId);
    }

    @Override public List<Event> findUpcomingOpen(Instant now, int limit) {
        return hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.state='OPEN' AND e.end_at>? ORDER BY e.start_at LIMIT ?", JdbcEventRepository::mapRow, now, limit));
    }

    @Override public List<Event> findPublicByClub(long clubId) {
        return hydrate(db.list("SELECT " + COLS + " FROM event e WHERE e.host_club_id=? AND e.state IN ('PUBLISHED','OPEN','CLOSED','ONGOING','COMPLETED') "
                + "ORDER BY e.start_at DESC", JdbcEventRepository::mapRow, clubId));
    }
}

/**
 * Compare-and-set updates: each statement states the expected current state in its WHERE clause, so running it twice, or
 * from two clients at once, has the same effect as running it once (NFR-REL-05, R-04).
 */
class JdbcSchedulerRepository implements SchedulerRepository {
    private final Jdbc db;
    public JdbcSchedulerRepository(Jdbc db) { this.db = db; }

    private List<Long> cas(String to, String where, Object... args) {
        return db.list("UPDATE event SET state='" + to + "', version=version+1 WHERE " + where + " RETURNING event_id", rs -> rs.getLong(1), args);
    }
    @Override public List<Long> openPublished(Instant now) { return cas("OPEN", "state='PUBLISHED' AND (reg_opens_at IS NULL OR reg_opens_at<=?)", now); }
    @Override public List<Long> closeOpen(Instant now) { return cas("CLOSED", "state='OPEN' AND COALESCE(reg_deadline_at, start_at)<=?", now); }
    @Override public List<Long> startEvents(Instant now) { return cas("ONGOING", "state IN ('OPEN','CLOSED') AND start_at<=?", now); }
    @Override public List<Long> completeEvents(Instant now) { return cas("COMPLETED", "state='ONGOING' AND end_at<=?", now); }

    @Override public List<DueReminder> findDueReminders(Instant from, Instant to) {
        return db.list("SELECT e.event_id, 0 AS sid, e.start_at FROM event e WHERE e.event_type<>'WORKSHOP' AND e.state IN ('PUBLISHED','OPEN','CLOSED') AND e.start_at>? AND e.start_at<=? "
                + "UNION ALL SELECT e.event_id, s.session_id, s.start_at FROM workshop_session s JOIN event e ON e.event_id=s.event_id WHERE e.state IN ('PUBLISHED','OPEN','CLOSED') AND s.start_at>? AND s.start_at<=?",
                rs -> new DueReminder(rs.getLong(1), rs.getLong(2), Jdbc.instant(rs, "start_at")), from, to, from, to);
    }
    @Override public boolean tryLogReminder(long eventId, long sessionId, Instant at) {
        return db.exec(db.dialect().insertIgnore("INSERT INTO reminder_log (event_id, session_id, sent_at) VALUES (?,?,?)", "event_id, session_id"), eventId, sessionId, at) > 0;
    }
    @Override public int applyRetention(Instant before) {
        return db.exec("UPDATE feedback SET comment=NULL WHERE comment IS NOT NULL AND updated_at<?", before)
             + db.exec("UPDATE registration SET cancel_reason=NULL WHERE cancel_reason IS NOT NULL AND registered_at<?", before);
    }
}

// ========== SERVICES ==========

/** Categories and the controlled tag list, maintained by Staff (FR-EVT-07). */
class CatalogService {
    private final TransactionManager tx;
    private final CatalogRepository catalog;
    private final AuthorizationService authz;

    public CatalogService(TransactionManager tx, CatalogRepository catalog, AuthorizationService authz) { this.tx = tx; this.catalog = catalog; this.authz = authz; }

    public List<CatalogRepository.Item> categories(UserSession s) { authz.requireSession(s); return tx.inTransaction(catalog::findCategories); }
    public List<CatalogRepository.Item> tags(UserSession s) { authz.requireSession(s); return tx.inTransaction(catalog::findTags); }
    /** Names of categories and tags for guests too (public list filters). */
    public List<CatalogRepository.Item> publicCategories() { return tx.inTransaction(catalog::findCategories); }

    public CatalogRepository.Item addCategory(UserSession s, LocalizedText name) {
        authz.require(s, Permission.MANAGE_EVENTS);
        if (name.en().isEmpty() || name.en().length() > 80) throw new ValidationException("error.catalog.name");
        return tx.inTransaction(() -> catalog.saveCategory(name));
    }
    public CatalogRepository.Item addTag(UserSession s, LocalizedText name) {
        authz.require(s, Permission.MANAGE_EVENTS);
        if (name.en().isEmpty() || name.en().length() > 60) throw new ValidationException("error.catalog.name");
        return tx.inTransaction(() -> catalog.saveTag(name));
    }
}

/** Registration numbers for organizers (FR-EVT-09). */
record EventDashboard(int confirmed, int waitlisted, int attended, int capacity) {}

/**
 * Application service for events: drafting, publishing, proposals, editing, cancellation and recurring series
 * (UC-07, UC-08; FR-EVT, FR-LCY, FR-REC). Every method checks authorization first, then runs in one transaction.
 */
class EventService {
    private static final int MAX_POSTER_BYTES = 2 * 1024 * 1024;
    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final ClubRepository clubs;
    private final UserRepository users;
    private final VenueService venueService;
    private final WaitlistService waitlist;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public EventService(TransactionManager tx, EventRepository events, RegistrationRepository regs, ClubRepository clubs, UserRepository users,
                        VenueService venueService, WaitlistService waitlist, AuthorizationService authz, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.clubs = clubs; this.users = users;
        this.venueService = venueService; this.waitlist = waitlist; this.authz = authz; this.publisher = publisher; this.clock = clock;
    }

    // ------------------------------------------------------------------ reading
    /** Loads an event. Drafts and pending proposals are visible only to their creator, Staff and the host club's officers. */
    public Event get(UserSession s, long id) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Event e = events.findById(id).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            if (!e.status().isPublic() && e.getCreatedBy() != s.getUserId()) authz.requireOrganizer(s, e);
            return e;
        });
    }

    /** Public view for guests: only events in public states (FR-USR / UC-03, guests may browse published events). */
    public Event getPublic(long id) {
        return tx.inTransaction(() -> {
            Event e = events.findById(id).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            if (!e.status().isPublic() || e.status() == EventStatus.CANCELLED || e.status() == EventStatus.COMPLETED) throw new AuthorizationException("error.forbidden");
            return e;
        });
    }

    /** Position of an occurrence within its series: {index, total}, or null when not part of a series (FR-REC-07). */
    public int[] seriesPosition(UserSession s, Event e) {
        authz.requireSession(s);
        if (e.getSeriesId() == null) return null;
        return tx.inTransaction(() -> new int[]{e.getSeriesIndex(), events.findBySeries(e.getSeriesId()).size()});
    }

    /** The occurrences of the event's series, in order. */
    public List<Event> seriesEvents(UserSession s, Event e) {
        authz.requireSession(s);
        if (e.getSeriesId() == null) return List.of(e);
        return tx.inTransaction(() -> events.findBySeries(e.getSeriesId()));
    }

    public Optional<EventRepository.Poster> poster(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> events.findPoster(eventId));
    }

    public List<EventRepository.StateChange> history(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> { authz.requireOrganizer(s, events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"))); return events.findHistory(eventId); });
    }

    /** Registration numbers (FR-EVT-09). */
    public EventDashboard dashboard(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            authz.requireOrganizer(s, e);
            int[] c = regs.countsFor(List.of(eventId)).get(eventId);
            return new EventDashboard(c[0], c[1], c[2], e.getCapacity());
        });
    }

    // ------------------------------------------------------------------ drafting
    /** Creates a Draft. Staff create events; officers create proposals for their own club (FR-LCY-02). */
    public Event create(UserSession s, EventData d) {
        authz.requireSession(s);
        boolean staff = s.getAccountType().basePermissions().contains(Permission.MANAGE_EVENTS);
        if (!staff) authz.require(s, Permission.PROPOSE_EVENT, d.hostClubId);
        if (!staff && d.hostClubId == null) throw new AuthorizationException("error.forbidden");
        checkPoster(d);
        return tx.inTransaction(() -> {
            checkClub(d.hostClubId);
            Event e = EventFactory.create(d, s.getUserId(), !staff);
            events.save(e);
            savePoster(e, d);
            events.recordHistory(e.getId(), null, EventStatus.DRAFT, s.getUserId(), null, clock.instant());
            return e;
        });
    }

    private void checkClub(Long clubId) {
        if (clubId != null && clubs.findById(clubId).filter(c -> c.isActive()).isEmpty()) throw new ValidationException("error.club.inactive");
    }
    private void checkPoster(EventData d) {
        if (d.poster == null) return;
        if (d.poster.length > MAX_POSTER_BYTES) throw new ValidationException("error.poster.size");
        if (!"image/png".equals(d.posterType) && !"image/jpeg".equals(d.posterType)) throw new ValidationException("error.poster.type");
    }
    private void savePoster(Event e, EventData d) {
        if (d.clearPoster) events.deletePoster(e.getId());
        if (d.poster != null) events.savePoster(e.getId(), d.posterType, d.poster);
    }

    /**
     * Edits an event. Drafts: the creator (officers: own drafts only) or Staff for non-proposals. Published, Open or Closed events: Staff only.
     * A change of time, venue or sessions notifies registrants (NT-07) and re-checks the venue; raising the capacity promotes from the waitlist (FR-EVT-04).
     */
    public Event edit(UserSession s, long eventId, int expectedVersion, EventData d) {
        authz.requireSession(s);
        checkPoster(d);
        return tx.inTransaction(() -> {
            Event e = events.lockById(eventId);
            if (e.getVersion() != expectedVersion) throw new StaleDataException("error.stale");
            requireEditRight(s, e);
            applyEdit(s, e, d);
            savePoster(e, d);
            return e;
        });
    }

    private void requireEditRight(UserSession s, Event e) {
        boolean staff = s.getAccountType().basePermissions().contains(Permission.MANAGE_EVENTS);
        switch (e.status()) {
            case DRAFT -> {
                if (e.isProposal() || e.getCreatedBy() == s.getUserId()) {
                    if (e.getCreatedBy() != s.getUserId()) throw new AuthorizationException("error.forbidden");
                    if (!staff) authz.require(s, Permission.PROPOSE_EVENT, e.getHostClubId());
                } else if (!staff) throw new AuthorizationException("error.forbidden");
            }
            case PUBLISHED, OPEN, CLOSED -> { if (!staff) throw new AuthorizationException("error.forbidden"); }
            default -> throw new IllegalTransitionException("error.event.notEditable", e.status().name());
        }
    }

    private void applyEdit(UserSession s, Event e, EventData d) {
        if (d.type != e.type()) throw new ValidationException("error.event.typeChange");
        checkClub(d.hostClubId);
        List<TimeSlot> oldSlots = e.getTimeSlots();
        Long oldVenue = e.getVenueId();
        List<Long> oldSessionVenues = sessionVenues(e);
        int oldCapacity = e.getCapacity();
        e.applyData(d);
        e.validateBasics();
        boolean live = e.status() != EventStatus.DRAFT;
        if (live) {
            int held = regs.countSeatsHeld(e.getId());
            if (e.getCapacity() < held) throw new ValidationException("error.event.capacityBelowConfirmed", held);
            Venue v = reserveVenues(e, e.getId());
            e.validateForPublish(clock.instant(), v.getCapacity());
        }
        events.save(e);
        boolean changed = !oldSlots.equals(e.getTimeSlots()) || !Objects.equals(oldVenue, e.getVenueId()) || !oldSessionVenues.equals(sessionVenues(e));
        if (live && changed) {
            List<Long> to = regs.findUserIds(e.getId(), RegistrationStatus.CONFIRMED, RegistrationStatus.WAITLISTED, RegistrationStatus.ATTENDED);
            publisher.publish(new EventChanged(e.getId(), e.getTitle(), to));
        }
        if (live && e.getCapacity() > oldCapacity) waitlist.promote(e);
    }

    private static List<Long> sessionVenues(Event e) {
        List<Long> l = new ArrayList<>();
        if (e instanceof Workshop w) for (WorkshopSession x : w.getSessions()) l.add(x.getVenueId());
        return l;
    }

    /** Reserves the venue(s) for all slots (workshop sessions may use other venues). Returns the main venue. */
    Venue reserveVenues(Event e, Long ignoreId) {
        if (e.getVenueId() == null) throw new ValidationException("error.event.venueRequired");
        Map<Long, List<TimeSlot>> byVenue = new LinkedHashMap<>();
        if (e instanceof Workshop w) {
            for (WorkshopSession x : w.getSessions())
                byVenue.computeIfAbsent(x.getVenueId() != null ? x.getVenueId() : e.getVenueId(), k -> new ArrayList<>()).addAll(x.getTimeSlots());
            byVenue.computeIfAbsent(e.getVenueId(), k -> new ArrayList<>());
        } else byVenue.put(e.getVenueId(), e.getTimeSlots());
        Venue main = null;
        List<Long> order = new ArrayList<>(byVenue.keySet());
        Collections.sort(order);                       // consistent lock order avoids deadlocks
        for (Long vid : order) {
            Venue v = venueService.reserve(vid, byVenue.get(vid), ignoreId);
            if (vid.equals(e.getVenueId())) main = v;
        }
        return main;
    }

    // ------------------------------------------------------------------ lifecycle
    private void record(Event e, EventStatus from, UserSession s, String note) {
        events.recordHistory(e.getId(), from, e.status(), s == null ? null : s.getUserId(), note, clock.instant());
    }

    private void publishChecks(Event e) {
        Venue v = reserveVenues(e, e.getId());
        e.validateForPublish(clock.instant(), v.getCapacity());
    }

    /** Staff publish their own (non-proposal) draft directly (FR-LCY-05). Checks FR-EVT-03 and reserves the venue (FR-LCY-06). */
    public void publish(UserSession s, long eventId) {
        authz.require(s, Permission.MANAGE_EVENTS);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            if (e.isProposal()) throw new ConflictException("error.event.useProposal");
            doPublish(s, e);
        });
    }

    private void doPublish(UserSession s, Event e) {
        EventStatus from = e.status();
        publishChecks(e);
        e.publish();
        events.save(e);
        record(e, from, s, null);
    }

    /** Officer submits an own draft proposal for approval (FR-LCY-02). */
    public void submit(UserSession s, long eventId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            authz.require(s, Permission.PROPOSE_EVENT, e.getHostClubId());
            if (!e.isProposal() || e.getCreatedBy() != s.getUserId()) throw new AuthorizationException("error.forbidden");
            if (e.getVenueId() == null) throw new ValidationException("error.event.venueRequired");
            Venue v = venueService.checkAvailability0(e.getVenueId());
            e.validateForPublish(clock.instant(), v.getCapacity());
            List<Booking> conflicts = new ArrayList<>();
            for (TimeSlot t : e.getTimeSlots()) conflicts.addAll(events.findBlocking(e.getVenueId(), t, e.getId()));
            if (!conflicts.isEmpty()) throw new VenueConflictException("error.venue.conflict", conflicts);
            EventStatus from = e.status();
            e.submit();
            events.save(e);
            record(e, from, s, null);
            LocalizedText club = clubs.findNames(List.of(e.getHostClubId())).getOrDefault(e.getHostClubId(), LocalizedText.EMPTY);
            publisher.publish(new ProposalSubmitted(e.getId(), e.getTitle(), club, users.findActiveStaffIds()));
        });
    }

    /** Officer withdraws a pending proposal back to Draft (FR-LCY-04). */
    public void withdraw(UserSession s, long eventId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            authz.require(s, Permission.PROPOSE_EVENT, e.getHostClubId());
            if (e.getCreatedBy() != s.getUserId()) throw new AuthorizationException("error.forbidden");
            EventStatus from = e.status();
            e.withdraw();
            events.save(e);
            record(e, from, s, null);
        });
    }

    /** Staff approve a proposal: the venue is re-checked and reserved and the event becomes Published (FR-LCY-03, SUC-4). */
    public void approve(UserSession s, long eventId) {
        authz.require(s, Permission.APPROVE_PROPOSALS);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            if (e.status() != EventStatus.PENDING_APPROVAL) throw new IllegalTransitionException("error.event.transition", "approve", e.status().name());
            EventStatus from = e.status();
            publishChecks(e);
            e.approve();
            e.setRejectionReason(null);
            events.save(e);
            record(e, from, s, null);
            publisher.publish(new ProposalDecided(s.getUserId(), e.getId(), e.getTitle(), e.getCreatedBy(), true, null));
        });
    }

    /** Staff reject a proposal with a mandatory reason; it returns to Draft (FR-LCY-03). */
    public void reject(UserSession s, long eventId, String reason) {
        authz.require(s, Permission.APPROVE_PROPOSALS);
        if (reason == null || reason.isBlank()) throw new ValidationException("error.reason.required");
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            EventStatus from = e.status();
            e.reject();
            e.setRejectionReason(reason.trim());
            events.save(e);
            record(e, from, s, reason.trim());
            publisher.publish(new ProposalDecided(s.getUserId(), e.getId(), e.getTitle(), e.getCreatedBy(), false, reason.trim()));
        });
    }

    /** Staff close registration manually (FR-LCY-07). */
    public void closeRegistration(UserSession s, long eventId) {
        authz.require(s, Permission.MANAGE_EVENTS);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            EventStatus from = e.status();
            e.close();
            events.save(e);
            record(e, from, s, null);
        });
    }

    /** Staff cancel an event with a mandatory reason; all active registrations are cancelled and registrants notified (FR-EVT-05). */
    public void cancel(UserSession s, long eventId, String reason) {
        authz.require(s, Permission.MANAGE_EVENTS);
        if (reason == null || reason.isBlank()) throw new ValidationException("error.reason.required");
        tx.runInTransaction(() -> cancelInTx(s, eventId, reason.trim()));
    }

    private void cancelInTx(UserSession s, long eventId, String reason) {
        Event e = events.lockById(eventId);
        EventStatus from = e.status();
        e.cancel();
        e.setCancelReason(reason);
        events.save(e);
        List<Long> affected = regs.cancelAllActive(e.getId(), clock.instant(), s.getUserId(), reason);
        record(e, from, s, reason);
        publisher.publish(new EventCancelled(s.getUserId(), e.getId(), e.getTitle(), affected, reason));
    }

    /** Deletes a Draft (FR-EVT-06, FR-LCY-04): the creator, or Staff for non-proposals. Other states are kept. */
    public void delete(UserSession s, long eventId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            if (e.status() != EventStatus.DRAFT) throw new IllegalTransitionException("error.event.notDeletable", e.status().name());
            requireEditRight(s, e);
            events.delete(eventId);
        });
    }

    /** Staff copy an event into a new Draft (FR-EVT-10). */
    public Event copy(UserSession s, long eventId) {
        authz.require(s, Permission.MANAGE_EVENTS);
        return tx.inTransaction(() -> {
            Event src = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            EventData d = toData(src);
            d.title = new LocalizedText(src.getTitle().en().isEmpty() ? "" : src.getTitle().en() + " (copy)", src.getTitle().ar().isEmpty() ? "" : src.getTitle().ar() + " (نسخة)");
            Event e = EventFactory.create(d, s.getUserId(), false);
            events.save(e);
            events.recordHistory(e.getId(), null, EventStatus.DRAFT, s.getUserId(), "copy of " + eventId, clock.instant());
            return e;
        });
    }

    /** Converts an event back to input data (used by copy and by the editor). */
    public static EventData toData(Event e) { return toData(e, false); }

    /** Same, optionally keeping workshop session ids so an edit updates the sessions instead of replacing them. */
    public static EventData toData(Event e, boolean keepSessionIds) {
        EventData d = new EventData();
        d.type = e.type(); d.title = e.getTitle(); d.description = e.getDescription(); d.categoryId = e.getCategoryId();
        d.tagIds = new HashSet<>(e.getTagIds()); d.startAt = e.getStartAt(); d.endAt = e.getEndAt(); d.venueId = e.getVenueId();
        d.capacity = e.getCapacity(); d.hostClubId = e.getHostClubId(); d.regOpensAt = e.getRegOpensAt();
        d.regDeadlineAt = e.getRegDeadlineAt(); d.cancelDeadlineAt = e.getCancelDeadlineAt();
        if (e instanceof Workshop w) {
            d.sessions = new ArrayList<>();
            for (WorkshopSession x : w.getSessions()) d.sessions.add(new SessionData(keepSessionIds ? x.getId() : null, x.getTopic(), x.getSlot().start(), x.getSlot().end(), x.getVenueId()));
        }
        return d;
    }

    // ------------------------------------------------------------------ announcements
    /** Organizers send an announcement to the event's registrants (FR-NTF-07, NT-15). */
    public int announce(UserSession s, long eventId, LocalizedText subject, LocalizedText message) {
        authz.requireSession(s);
        if (subject.isBlank() || message.isBlank()) throw new ValidationException("error.announcement.required");
        if (message.en().length() > 1000 || message.ar().length() > 1000 || subject.en().length() > 200 || subject.ar().length() > 200) throw new ValidationException("error.text.tooLong", "message", 1000);
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            authz.requireOrganizer(s, e);
            List<Long> to = regs.findUserIds(eventId, RegistrationStatus.CONFIRMED, RegistrationStatus.WAITLISTED, RegistrationStatus.ATTENDED);
            publisher.publish(new AnnouncementSent("event:" + eventId, subject, message, to));
            return to.size();
        });
    }

    // ------------------------------------------------------------------ recurring series
    /** Lists the occurrences a recurrence would create and the venue conflicts of each (FR-REC-03). */
    public List<SeriesOccurrence> previewSeries(UserSession s, EventData d, RecurrenceRule rule) {
        authz.require(s, Permission.MANAGE_EVENTS);
        return tx.inTransaction(() -> plan(d, rule));
    }

    private List<SeriesOccurrence> plan(EventData d, RecurrenceRule rule) {
        if (d.type != EventType.GENERAL) throw new ValidationException("error.recurrence.generalOnly");   // BR-11
        if (d.startAt == null || d.endAt == null) throw new ValidationException("error.event.timesRequired");
        if (d.venueId == null) throw new ValidationException("error.event.venueRequired");
        List<TimeSlot> slots = rule.expand(new TimeSlot(d.startAt, d.endAt), Gst.ZONE);
        List<SeriesOccurrence> out = new ArrayList<>();
        int i = 1;
        for (TimeSlot t : slots) out.add(new SeriesOccurrence(i++, t, events.findBlocking(d.venueId, t, null)));
        return out;
    }

    /**
     * Creates a series atomically: all accepted occurrences or none (FR-REC-04). Conflicting dates must be listed in {@code skip}
     * (by occurrence index), otherwise a VenueConflictException lists them. With {@code publish} the occurrences are published too.
     */
    public List<Event> createSeries(UserSession s, EventData d, RecurrenceRule rule, Set<Integer> skip, boolean publish) {
        authz.require(s, Permission.MANAGE_EVENTS);
        checkPoster(d);
        return tx.inTransaction(() -> {
            checkClub(d.hostClubId);
            List<SeriesOccurrence> plan = plan(d, rule);
            List<Integer> bad = new ArrayList<>();
            for (SeriesOccurrence o : plan) if (o.hasConflict() && !skip.contains(o.index())) bad.add(o.index());
            if (!bad.isEmpty()) throw new VenueConflictException("error.series.conflicts", bad);
            long seriesId = events.saveSeries(new EventSeries(rule));
            List<Event> created = new ArrayList<>();
            int n = 0;
            for (SeriesOccurrence o : plan) {
                if (skip.contains(o.index())) continue;
                EventData od = d.copy();
                Duration shift = Duration.between(d.startAt, o.slot().start());
                od.startAt = o.slot().start(); od.endAt = o.slot().end();
                if (d.regOpensAt != null) od.regOpensAt = d.regOpensAt.plus(shift);
                if (d.regDeadlineAt != null) od.regDeadlineAt = d.regDeadlineAt.plus(shift);
                if (d.cancelDeadlineAt != null) od.cancelDeadlineAt = d.cancelDeadlineAt.plus(shift);
                Event e = EventFactory.create(od, s.getUserId(), false);
                e.setSeriesId(seriesId); e.setSeriesIndex(++n);
                events.save(e);
                savePoster(e, d);
                events.recordHistory(e.getId(), null, EventStatus.DRAFT, s.getUserId(), "series " + seriesId, clock.instant());
                if (publish) doPublish(s, e);
                created.add(e);
            }
            if (created.isEmpty()) throw new ValidationException("error.series.empty");
            return created;
        });
    }

    /** Edits one occurrence, or this and all following ones; the venue is re-checked for each (FR-REC-05). Following occurrences keep their dates. */
    public void editSeries(UserSession s, long eventId, int expectedVersion, EventData d, SeriesScope scope) {
        authz.require(s, Permission.MANAGE_EVENTS);
        if (scope == SeriesScope.THIS_ONLY) { edit(s, eventId, expectedVersion, d); return; }
        tx.runInTransaction(() -> {
            Event first = events.lockById(eventId);
            if (first.getVersion() != expectedVersion) throw new StaleDataException("error.stale");
            if (first.getSeriesId() == null) throw new ValidationException("error.series.notInSeries");
            LocalTime st = d.startAt.atZone(Gst.ZONE).toLocalTime();
            Duration len = Duration.between(d.startAt, d.endAt);
            for (Event occ : events.findBySeries(first.getSeriesId())) {
                if (occ.getSeriesIndex() < first.getSeriesIndex()) continue;
                Event e = occ.getId() == first.getId() ? first : events.lockById(occ.getId());
                if (e.status() != EventStatus.DRAFT && e.status() != EventStatus.PUBLISHED && e.status() != EventStatus.OPEN && e.status() != EventStatus.CLOSED) continue;
                EventData od = d.copy();
                if (e.getId() != first.getId()) {
                    Instant ns = ZonedDateTime.of(e.getStartAt().atZone(Gst.ZONE).toLocalDate(), st, Gst.ZONE).toInstant();
                    od.startAt = ns; od.endAt = ns.plus(len);
                    od.regOpensAt = e.getRegOpensAt(); od.regDeadlineAt = e.getRegDeadlineAt(); od.cancelDeadlineAt = e.getCancelDeadlineAt();
                }
                requireEditRight(s, e);
                applyEdit(s, e, od);
            }
        });
    }

    /** Cancels one occurrence or all remaining ones with a reason; Drafts of the series are deleted (FR-REC-06). */
    public void cancelSeries(UserSession s, long eventId, String reason, SeriesScope scope) {
        authz.require(s, Permission.MANAGE_EVENTS);
        if (reason == null || reason.isBlank()) throw new ValidationException("error.reason.required");
        tx.runInTransaction(() -> {
            Event first = events.lockById(eventId);
            if (scope == SeriesScope.THIS_ONLY || first.getSeriesId() == null) { cancelInTx(s, eventId, reason.trim()); return; }
            for (Event occ : events.findBySeries(first.getSeriesId())) {
                if (occ.getSeriesIndex() < first.getSeriesIndex()) continue;
                switch (occ.status()) {
                    case PUBLISHED, OPEN, CLOSED -> cancelInTx(s, occ.getId(), reason.trim());
                    case DRAFT -> events.delete(occ.getId());
                    default -> { }
                }
            }
        });
    }
}

/** One planned occurrence of a series and the bookings it would conflict with (FR-REC-03). */
record SeriesOccurrence(int index, TimeSlot slot, List<Booking> conflicts) {
    public boolean hasConflict() { return !conflicts.isEmpty(); }
}

/** Which occurrences of a recurring series an edit or cancellation applies to (FR-REC-05, FR-REC-06). */
enum SeriesScope { THIS_ONLY, THIS_AND_FOLLOWING }

/**
 * Time-driven work run by every client about once a minute: lifecycle transitions, reminders and clean-up
 * (FR-LCY-07, FR-LCY-08, FR-NTF-08, NT-08, NT-14). Every step is a compare-and-set update, so running it twice or
 * from two clients has the same effect as running it once (NFR-REL-05). Each step is its own transaction.
 */
class SchedulerService {
    /** Summary of one tick, for logs and tests. */
    public record TickResult(int opened, int closed, int started, int completed, int reminders, int purged) {}

    private final TransactionManager tx;
    private final SchedulerRepository sched;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final NotificationRepository notifications;
    private final CatalogRepository catalog;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public SchedulerService(TransactionManager tx, SchedulerRepository sched, EventRepository events, RegistrationRepository regs,
                            NotificationRepository notifications, CatalogRepository catalog, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.sched = sched; this.events = events; this.regs = regs; this.notifications = notifications;
        this.catalog = catalog; this.publisher = publisher; this.clock = clock;
    }

    /** Runs all steps once. A failing step is skipped and reported through the exception of the first failure after the others ran. */
    public TickResult tick() {
        Instant now = clock.instant();
        RuntimeException failure = null;
        int[] n = new int[6];
        try { n[0] = step(now, EventStatus.PUBLISHED, EventStatus.OPEN); } catch (RuntimeException e) { failure = e; }
        try { n[1] = step(now, EventStatus.OPEN, EventStatus.CLOSED); } catch (RuntimeException e) { if (failure == null) failure = e; }
        try { n[2] = step(now, null, EventStatus.ONGOING); } catch (RuntimeException e) { if (failure == null) failure = e; }
        try { n[3] = step(now, EventStatus.ONGOING, EventStatus.COMPLETED); } catch (RuntimeException e) { if (failure == null) failure = e; }
        try { n[4] = reminders(now); } catch (RuntimeException e) { if (failure == null) failure = e; }
        try { n[5] = cleanup(now); } catch (RuntimeException e) { if (failure == null) failure = e; }
        if (failure != null) throw failure;
        return new TickResult(n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    private int step(Instant now, EventStatus from, EventStatus to) {
        return tx.inTransaction(() -> {
            List<Long> ids = switch (to) {
                case OPEN -> sched.openPublished(now);
                case CLOSED -> sched.closeOpen(now);
                case ONGOING -> sched.startEvents(now);
                case COMPLETED -> sched.completeEvents(now);
                default -> List.<Long>of();
            };
            for (Long id : ids) {
                events.recordHistory(id, from, to, null, "scheduler", now);
                if (to == EventStatus.COMPLETED) {
                    Event e = events.findById(id).orElse(null);
                    List<Long> attended = regs.findUserIds(id, RegistrationStatus.ATTENDED);
                    if (e != null && !attended.isEmpty()) publisher.publish(new FeedbackRequested(id, e.getTitle(), attended));   // NT-14
                }
            }
            return ids.size();
        });
    }

    private int reminders(Instant now) {
        return tx.inTransaction(() -> {
            int sent = 0;
            for (SchedulerRepository.DueReminder d : sched.findDueReminders(now, now.plus(Duration.ofHours(24)))) {
                if (!sched.tryLogReminder(d.eventId(), d.sessionId(), now)) continue;                 // already sent by someone else
                Event e = events.findById(d.eventId()).orElse(null);
                if (e == null) continue;
                List<Long> to = regs.findUserIds(d.eventId(), RegistrationStatus.CONFIRMED);
                if (!to.isEmpty()) publisher.publish(new EventReminder(d.eventId(), e.getTitle(), d.startsAt(), to));   // NT-08
                sent++;
            }
            return sent;
        });
    }

    private int cleanup(Instant now) {
        return tx.inTransaction(() -> {
            int purged = notifications.purgeOlderThan(now.minus(Duration.ofDays(90)));                  // FR-NTF-08
            int years = catalog.getSetting("retention.years").map(Integer::parseInt).orElse(3);
            Instant cutoff = now.atOffset(ZoneOffset.UTC).minusYears(years).toInstant();
            return purged + sched.applyRetention(cutoff);                                                // NFR-PRV-04
        });
    }
}
