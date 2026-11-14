package uems;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// MODULE 2 SERVICES: events and lifecycle, registration and waitlist, check-in, scheduler, calendar export.

// ======== service.event ========

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
 * Add, edit and remove workshop sessions (UC-12, FR-WSH-02). Delegates to {@link EventService#edit} so permissions,
 * non-overlap, per-session venue checks and NT-07 are applied in exactly one place.
 */
class WorkshopService {
    private final TransactionManager tx;
    private final EventRepository events;
    private final EventService eventService;

    public WorkshopService(TransactionManager tx, EventRepository events, EventService eventService) {
        this.tx = tx; this.events = events; this.eventService = eventService;
    }

    private Workshop load(long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
        if (!(e instanceof Workshop w)) throw new ValidationException("error.workshop.notWorkshop");
        return w;
    }

    public Event addSession(UserSession s, long eventId, SessionData sd) {
        return tx.inTransaction(() -> {
            Workshop w = load(eventId);
            EventData d = EventService.toData(w, true);
            d.sessions.add(new SessionData(null, sd.topic(), sd.start(), sd.end(), sd.venueId()));
            return eventService.edit(s, eventId, w.getVersion(), d);
        });
    }

    public Event updateSession(UserSession s, long eventId, long sessionId, SessionData sd) {
        return tx.inTransaction(() -> {
            Workshop w = load(eventId);
            EventData d = EventService.toData(w, true);
            d.sessions = new ArrayList<>(d.sessions.stream().map(x -> x.id() != null && x.id() == sessionId ? new SessionData(sessionId, sd.topic(), sd.start(), sd.end(), sd.venueId()) : x).toList());
            return eventService.edit(s, eventId, w.getVersion(), d);
        });
    }

    public Event removeSession(UserSession s, long eventId, long sessionId) {
        return tx.inTransaction(() -> {
            Workshop w = load(eventId);
            EventData d = EventService.toData(w, true);
            d.sessions = new ArrayList<>(d.sessions.stream().filter(x -> x.id() == null || x.id() != sessionId).toList());
            return eventService.edit(s, eventId, w.getVersion(), d);
        });
    }
}

// ======== service.registration ========

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

// ======== service.scheduler ========

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

// ======== service.search ========

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
