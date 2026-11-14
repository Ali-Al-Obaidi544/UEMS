package uems;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

// MODULE 3 SERVICES: clubs, competition results, feedback, discussion, search and recommendations.

// ======== service.club ========

/** Everything shown on a club page (FR-CLB-08). */
record ClubDetails(Club club, List<MemberRow> officers, int memberCount, List<Event> events, ClubMembership mine) {}

/** A club with its member count and the caller's own membership (club list, FR-CLB-03). */
record ClubInfo(Club club, int members, MembershipStatus myStatus, MembershipRole myRole) {}

/** Clubs, membership and officer workflows (UC-10, UC-11; FR-CLB-01..09). */
class ClubService {
    private final TransactionManager tx;
    private final ClubRepository clubs;
    private final UserRepository users;
    private final EventRepository events;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public ClubService(TransactionManager tx, ClubRepository clubs, UserRepository users, EventRepository events,
                       AuthorizationService authz, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.clubs = clubs; this.users = users; this.events = events; this.authz = authz; this.publisher = publisher; this.clock = clock;
    }

    // ---- browsing ----
    /** All clubs with member counts and the caller's membership. */
    public List<ClubInfo> list(UserSession s, boolean activeOnly) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            List<ClubInfo> out = new ArrayList<>();
            for (Club c : clubs.findAll(activeOnly)) {
                Optional<ClubMembership> m = clubs.findOpenMembership(c.getId(), s.getUserId());
                out.add(new ClubInfo(c, clubs.countMembers(c.getId()), m.map(ClubMembership::getStatus).orElse(null), m.map(ClubMembership::getRole).orElse(null)));
            }
            return out;
        });
    }

    /** The club page (FR-CLB-08). */
    public ClubDetails details(UserSession s, long clubId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Club c = clubs.findById(clubId).orElseThrow(() -> new NotFoundException("error.club.notFound"));
            return new ClubDetails(c, clubs.findOfficers(clubId), clubs.countMembers(clubId), events.findPublicByClub(clubId), clubs.findOpenMembership(clubId, s.getUserId()).orElse(null));
        });
    }

    /** Clubs where the caller is an active officer; used to build the navigation (BR-14). */
    public List<Long> officerClubIds(UserSession s) {
        authz.requireSession(s);
        return tx.inTransaction(() -> clubs.findOfficerClubIds(s.getUserId()));
    }

    public Map<Long, LocalizedText> names(UserSession s, Collection<Long> ids) {
        authz.requireSession(s);
        return tx.inTransaction(() -> clubs.findNames(ids));
    }

    // ---- staff: club administration ----
    /** Staff create a club with its first officer (an active club always has an officer, FR-CLB-02). */
    public Club create(UserSession s, Club c, long firstOfficerId) {
        authz.require(s, Permission.MANAGE_CLUBS);
        validate(c);
        return tx.inTransaction(() -> {
            clubs.save(c);
            appoint(s, c, firstOfficerId);
            return c;
        });
    }

    public Club update(UserSession s, Club c) {
        authz.require(s, Permission.MANAGE_CLUBS);
        validate(c);
        return tx.inTransaction(() -> clubs.save(c));
    }

    /** Deactivates a club; its history is kept. */
    public void deactivate(UserSession s, long clubId) {
        authz.require(s, Permission.MANAGE_CLUBS);
        tx.runInTransaction(() -> { Club c = clubs.lockById(clubId); c.setActive(false); clubs.save(c); });
    }

    private void validate(Club c) {
        if (c.getName().isBlank()) throw new ValidationException("error.club.nameRequired");
        if (c.getName().en().length() > 120 || c.getDescription().en().length() > 2000 || c.getDescription().ar().length() > 2000)
            throw new ValidationException("error.text.tooLong", "club", 2000);
    }

    /** Staff appoint an officer; Staff accounts are refused (BR-01). */
    public void appointOfficer(UserSession s, long clubId, long userId) {
        authz.require(s, Permission.MANAGE_CLUBS);
        tx.runInTransaction(() -> appoint(s, clubs.lockById(clubId), userId));
    }

    private void appoint(UserSession s, Club club, long userId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("error.user.notFound"));
        if (u.accountType() == AccountType.STAFF) throw new ValidationException("error.club.staffOfficer");
        if (u.getStatus() != UserStatus.ACTIVE) throw new ValidationException("error.user.inactive");
        ClubMembership m = clubs.findOpenMembership(club.getId(), userId).orElseGet(() -> {
            ClubMembership n = new ClubMembership();
            n.setClubId(club.getId()); n.setUserId(userId); n.setRequestedAt(clock.instant());
            return n;
        });
        if (m.getRole() == MembershipRole.OFFICER && m.getStatus() == MembershipStatus.ACTIVE) throw new ConflictException("error.club.alreadyOfficer");
        m.setRole(MembershipRole.OFFICER); m.setStatus(MembershipStatus.ACTIVE);
        m.setDecidedAt(clock.instant()); if (m.getJoinedAt() == null) m.setJoinedAt(clock.instant());
        clubs.saveMembership(m);
        publisher.publish(new OfficerChanged(s.getUserId(), club.getId(), club.getName(), userId, true));
    }

    /** Staff remove an officer; at least one officer must remain in an active club (FR-CLB-02). */
    public void removeOfficer(UserSession s, long clubId, long userId) {
        authz.require(s, Permission.MANAGE_CLUBS);
        tx.runInTransaction(() -> {
            Club c = clubs.lockById(clubId);
            ClubMembership m = clubs.findOpenMembership(clubId, userId).filter(x -> x.getRole() == MembershipRole.OFFICER && x.getStatus() == MembershipStatus.ACTIVE)
                    .orElseThrow(() -> new NotFoundException("error.club.notOfficer"));
            if (c.isActive() && clubs.countOfficers(clubId) <= 1) throw new ConflictException("error.club.lastOfficer");
            User u = users.findById(userId).orElseThrow();
            if (u.accountType() == AccountType.ADMIN) { m.setStatus(MembershipStatus.ENDED); }   // BR-01: an Admin is never an ordinary member
            else m.setRole(MembershipRole.MEMBER);
            m.setDecidedAt(clock.instant());
            clubs.saveMembership(m);
            publisher.publish(new OfficerChanged(s.getUserId(), clubId, c.getName(), userId, false));
        });
    }

    // ---- students ----
    /** A student asks to join; the request stays Pending until an officer decides (FR-CLB-03). */
    public void requestJoin(UserSession s, long clubId) {
        authz.require(s, Permission.JOIN_CLUB);
        tx.runInTransaction(() -> {
            Club c = clubs.lockById(clubId);
            if (!c.isActive()) throw new ValidationException("error.club.inactive");
            if (clubs.findOpenMembership(clubId, s.getUserId()).isPresent()) throw new ConflictException("error.club.alreadyMember");
            ClubMembership m = new ClubMembership();
            m.setClubId(clubId); m.setUserId(s.getUserId()); m.setRequestedAt(clock.instant());
            clubs.saveMembership(m);
            publisher.publish(new JoinRequestSubmitted(clubId, c.getName(), s.getDisplayName(), clubs.findOfficerUserIds(clubId)));
        });
    }

    /** A member leaves; the only officer cannot leave (FR-CLB-05). */
    public void leave(UserSession s, long clubId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Club c = clubs.lockById(clubId);
            ClubMembership m = clubs.findOpenMembership(clubId, s.getUserId()).orElseThrow(() -> new NotFoundException("error.club.notMember"));
            if (m.getStatus() == MembershipStatus.ACTIVE && m.getRole() == MembershipRole.OFFICER && c.isActive() && clubs.countOfficers(clubId) <= 1)
                throw new ConflictException("error.club.lastOfficer");
            m.setStatus(MembershipStatus.ENDED); m.setDecidedAt(clock.instant());
            clubs.saveMembership(m);
        });
    }

    // ---- officers ----
    /** Officers approve or reject a join request; the student is notified (FR-CLB-04). */
    public void decide(UserSession s, long membershipId, boolean approve, String reason) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            ClubMembership probe = clubs.findMembership(membershipId).orElseThrow(() -> new NotFoundException("error.club.requestNotFound"));
            authz.require(s, Permission.DECIDE_JOIN_REQUESTS, probe.getClubId());
            Club c = clubs.lockById(probe.getClubId());
            ClubMembership m = clubs.findMembership(membershipId).orElseThrow();
            if (m.getStatus() != MembershipStatus.PENDING) throw new ConflictException("error.club.alreadyDecided");
            m.setDecidedAt(clock.instant()); m.setReason(reason);
            if (approve) { m.setStatus(MembershipStatus.ACTIVE); m.setJoinedAt(clock.instant()); } else m.setStatus(MembershipStatus.REJECTED);
            clubs.saveMembership(m);
            publisher.publish(new JoinRequestDecided(c.getId(), c.getName(), m.getUserId(), approve, reason));
        });
    }

    /** Officers remove a regular member (FR-CLB-06). Officers are removed by Staff. */
    public void removeMember(UserSession s, long clubId, long userId) {
        authz.require(s, Permission.DECIDE_JOIN_REQUESTS, clubId);
        tx.runInTransaction(() -> {
            ClubMembership m = clubs.findOpenMembership(clubId, userId).filter(x -> x.getStatus() == MembershipStatus.ACTIVE).orElseThrow(() -> new NotFoundException("error.club.notMember"));
            if (m.getRole() == MembershipRole.OFFICER) throw new ConflictException("error.club.removeOfficerViaStaff");
            m.setStatus(MembershipStatus.ENDED); m.setDecidedAt(clock.instant());
            clubs.saveMembership(m);
        });
    }

    /** Member list with search (officers of the club and Staff, FR-CLB-09). */
    public List<MemberRow> members(UserSession s, long clubId, String search) {
        authz.requireSession(s);
        return tx.inTransaction(() -> { requireManagerView(s, clubId); return clubs.findMembers(clubId, search); });
    }

    /** Pending join requests (officers of the club). */
    public List<MemberRow> pendingRequests(UserSession s, long clubId) {
        authz.require(s, Permission.DECIDE_JOIN_REQUESTS, clubId);
        return tx.inTransaction(() -> clubs.findPending(clubId));
    }

    private void requireManagerView(UserSession s, long clubId) {
        if (s.getAccountType().basePermissions().contains(Permission.MANAGE_CLUBS)) return;
        authz.require(s, Permission.DECIDE_JOIN_REQUESTS, clubId);
    }

    /** Officers announce to the members of their club (FR-NTF-07). */
    public int announce(UserSession s, long clubId, LocalizedText subject, LocalizedText message) {
        authz.require(s, Permission.SEND_ANNOUNCEMENT, clubId);
        if (subject.isBlank() || message.isBlank()) throw new ValidationException("error.announcement.required");
        if (message.en().length() > 1000 || message.ar().length() > 1000) throw new ValidationException("error.text.tooLong", "message", 1000);
        return tx.inTransaction(() -> {
            List<Long> to = clubs.findMemberUserIds(clubId);
            publisher.publish(new AnnouncementSent("club:" + clubId, subject, message, to));
            return to.size();
        });
    }

}

// ======== service.social ========

/** Threaded event discussion with moderation (UC-15, FR-DSC-01..07). */
class DiscussionService {
    /** Marker stored as removal reason when authors delete their own comment. */
    public static final String REMOVED_BY_AUTHOR = "AUTHOR";
    private static final Duration READ_ONLY_AFTER = Duration.ofDays(30);
    private final TransactionManager tx;
    private final EventRepository events;
    private final SocialRepository social;
    private final UserRepository users;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public DiscussionService(TransactionManager tx, EventRepository events, SocialRepository social, UserRepository users,
                             AuthorizationService authz, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.events = events; this.social = social; this.users = users; this.authz = authz; this.publisher = publisher; this.clock = clock;
    }

    private Event visible(long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
        if (e.status() == EventStatus.DRAFT || e.status() == EventStatus.PENDING_APPROVAL) throw new ConflictException("error.discussion.unavailable");
        return e;
    }

    private boolean readOnly(Event e) {
        return e.status() == EventStatus.COMPLETED && clock.instant().isAfter(e.getEndAt().plus(READ_ONLY_AFTER));
    }

    /** True when the discussion no longer accepts comments (FR-DSC-07). */
    public boolean isReadOnly(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> readOnly(visible(eventId)));
    }

    /** All comments in chronological order; replies reference their parent. */
    public List<Comment> list(UserSession s, long eventId) {
        authz.require(s, Permission.COMMENT);
        return tx.inTransaction(() -> { visible(eventId); return social.findComments(eventId); });
    }

    /** Posts a comment, or a reply when {@code parentId} is given (one level only, FR-DSC-02). */
    public Comment post(UserSession s, long eventId, String body, Long parentId) {
        authz.require(s, Permission.COMMENT);
        String text = checkBody(body);
        return tx.inTransaction(() -> {
            Event e = visible(eventId);
            if (readOnly(e)) throw new ConflictException("error.discussion.readOnly");
            Comment parent = null;
            if (parentId != null) {
                parent = social.findComment(parentId).orElseThrow(() -> new NotFoundException("error.comment.notFound"));
                if (parent.getEventId() != eventId || parent.getParentId() != null) throw new ValidationException("error.comment.nesting");
            }
            Comment c = new Comment();
            c.setEventId(eventId); c.setUserId(s.getUserId()); c.setParentId(parentId); c.setBody(text); c.setCreatedAt(clock.instant());
            social.saveComment(c);
            if (parent != null && parent.getUserId() != s.getUserId() && !parent.isRemoved())
                publisher.publish(new CommentReplied(parent.getUserId(), eventId, e.getTitle(), s.getDisplayName()));
            return c;
        });
    }

    private static String checkBody(String body) {
        String t = body == null ? "" : body.trim();
        if (t.isEmpty()) throw new ValidationException("error.comment.empty");
        if (t.length() > 1000) throw new ValidationException("error.text.tooLong", "comment", 1000);
        return t;
    }

    /** Authors edit their own comment; it is marked as edited (FR-DSC-03). */
    public void edit(UserSession s, long commentId, String body) {
        authz.require(s, Permission.COMMENT);
        String text = checkBody(body);
        tx.runInTransaction(() -> {
            Comment c = own(s, commentId);
            if (readOnly(visible(c.getEventId()))) throw new ConflictException("error.discussion.readOnly");
            c.setBody(text); c.setEdited(true);
            social.saveComment(c);
        });
    }

    /** Authors delete their own comment. */
    public void deleteOwn(UserSession s, long commentId) {
        authz.require(s, Permission.COMMENT);
        tx.runInTransaction(() -> {
            Comment c = own(s, commentId);
            c.setRemoved(true); c.setRemovedReason(REMOVED_BY_AUTHOR);
            social.saveComment(c);
        });
    }

    private Comment own(UserSession s, long id) {
        Comment c = social.findComment(id).orElseThrow(() -> new NotFoundException("error.comment.notFound"));
        if (c.getUserId() != s.getUserId()) throw new AuthorizationException("error.forbidden");
        if (c.isRemoved()) throw new ConflictException("error.comment.removed");
        return c;
    }

    /** Staff remove any comment with a mandatory, logged reason; it shows "Removed by moderator" (FR-DSC-04). */
    public void moderate(UserSession s, long commentId, String reason) {
        authz.require(s, Permission.MODERATE_COMMENTS);
        if (reason == null || reason.isBlank()) throw new ValidationException("error.reason.required");
        tx.runInTransaction(() -> {
            Comment c = social.findComment(commentId).orElseThrow(() -> new NotFoundException("error.comment.notFound"));
            c.setRemoved(true); c.setRemovedReason(reason.trim());
            social.saveComment(c);
            publisher.publish(new CommentRemoved(s.getUserId(), commentId, c.getEventId(), reason.trim()));
        });
    }
}

/** Ratings and feedback after attended events (UC-15, FR-FBK-01..06, BR-09). */
class FeedbackService {
    public static final Duration EDIT_WINDOW = Duration.ofDays(7);
    public static final Duration SUBMIT_WINDOW = Duration.ofDays(30);
    /** What the caller can currently do for an event. */
    public enum Status { NOT_ELIGIBLE, CAN_SUBMIT, CAN_EDIT, SUBMITTED_LOCKED, WINDOW_CLOSED }

    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final SocialRepository social;
    private final AuthorizationService authz;
    private final Clock clock;

    public FeedbackService(TransactionManager tx, EventRepository events, RegistrationRepository regs, SocialRepository social, AuthorizationService authz, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.social = social; this.authz = authz; this.clock = clock;
    }

    private static void check(int rating, String comment) {
        if (rating < 1 || rating > 5) throw new ValidationException("error.feedback.rating");
        if (comment != null && comment.length() > 1000) throw new ValidationException("error.text.tooLong", "comment", 1000);
    }

    /** One feedback per student and event, only for Attended registrations of Completed events within 30 days (BR-09). */
    public Feedback submit(UserSession s, long eventId, int rating, String comment) {
        authz.require(s, Permission.GIVE_FEEDBACK);
        check(rating, comment);
        return tx.inTransaction(() -> {
            Event e = events.lockById(eventId);
            requireEligible(s, e);
            if (social.findFeedback(eventId, s.getUserId()).isPresent()) throw new ConflictException("error.feedback.exists");
            Feedback f = new Feedback();
            Instant now = clock.instant();
            f.setEventId(eventId); f.setUserId(s.getUserId()); f.setRating(rating);
            f.setComment(comment == null || comment.isBlank() ? null : comment.trim()); f.setCreatedAt(now); f.setUpdatedAt(now);
            return social.saveFeedback(f);
        });
    }

    private void requireEligible(UserSession s, Event e) {
        if (e.status() != EventStatus.COMPLETED) throw new ConflictException("error.feedback.notCompleted");
        Optional<Registration> r = regs.findActive(e.getId(), s.getUserId());
        if (r.isEmpty() || r.get().status() != RegistrationStatus.ATTENDED) throw new ConflictException("error.feedback.notAttended");
        if (clock.instant().isAfter(e.getEndAt().plus(SUBMIT_WINDOW))) throw new ConflictException("error.feedback.closed");
    }

    /** The author may edit for 7 days after submitting (FR-FBK-02). */
    public Feedback edit(UserSession s, long eventId, int rating, String comment) {
        authz.require(s, Permission.GIVE_FEEDBACK);
        check(rating, comment);
        return tx.inTransaction(() -> {
            Feedback f = social.findFeedback(eventId, s.getUserId()).orElseThrow(() -> new NotFoundException("error.feedback.notFound"));
            Instant now = clock.instant();
            if (now.isAfter(f.getCreatedAt().plus(EDIT_WINDOW))) throw new ConflictException("error.feedback.editClosed");
            f.setRating(rating); f.setComment(comment == null || comment.isBlank() ? null : comment.trim()); f.setUpdatedAt(now);
            return social.saveFeedback(f);
        });
    }

    public Optional<Feedback> mine(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> social.findFeedback(eventId, s.getUserId()));
    }

    /** Tells the UI which feedback action to offer. */
    public Status status(UserSession s, long eventId) {
        authz.requireSession(s);
        if (s.getAccountType() != AccountType.STUDENT) return Status.NOT_ELIGIBLE;
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            if (e.status() != EventStatus.COMPLETED) return Status.NOT_ELIGIBLE;
            Optional<Registration> r = regs.findActive(eventId, s.getUserId());
            if (r.isEmpty() || r.get().status() != RegistrationStatus.ATTENDED) return Status.NOT_ELIGIBLE;
            Instant now = clock.instant();
            Optional<Feedback> f = social.findFeedback(eventId, s.getUserId());
            if (f.isPresent()) return now.isAfter(f.get().getCreatedAt().plus(EDIT_WINDOW)) ? Status.SUBMITTED_LOCKED : Status.CAN_EDIT;
            return now.isAfter(e.getEndAt().plus(SUBMIT_WINDOW)) ? Status.WINDOW_CLOSED : Status.CAN_SUBMIT;
        });
    }

    /**
     * Average, count and distribution for everybody; comments only for Staff (with names) and officers of the host club
     * (without names, NFR-PRV-05) (FR-FBK-03, FR-FBK-04).
     */
    public FeedbackSummary summary(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
            List<Feedback> all = social.findFeedbackByEvent(eventId);
            int[] dist = new int[6];
            double sum = 0;
            for (Feedback f : all) { dist[f.getRating()]++; sum += f.getRating(); }
            boolean staff = s.getAccountType().basePermissions().contains(Permission.VIEW_FEEDBACK);
            boolean officer = !staff && e.getHostClubId() != null && authz.isOfficer(s, e.getHostClubId());
            List<Feedback> comments = new ArrayList<>();
            if (staff || officer) {
                for (Feedback f : all) {
                    if (f.getComment() == null) continue;
                    Feedback c = new Feedback();
                    c.setId(f.getId()); c.setEventId(eventId); c.setRating(f.getRating()); c.setComment(f.getComment()); c.setCreatedAt(f.getCreatedAt());
                    if (staff) { c.setUserId(f.getUserId()); c.setAuthorName(f.getAuthorName()); }
                    comments.add(c);
                }
            }
            return new FeedbackSummary(all.isEmpty() ? 0 : sum / all.size(), all.size(), dist, comments, staff);
        });
    }
}

/** Competition results: draft, publish and correct (UC-13, FR-CMP-02..07). */
class ResultService {
    /** One placement entered by an organizer. */
    public record Entry(long userId, int placement, BigDecimal score) {}

    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final ResultRepository results;
    private final AuthorizationService authz;
    private final DomainEventPublisher publisher;

    public ResultService(TransactionManager tx, EventRepository events, RegistrationRepository regs, ResultRepository results,
                         AuthorizationService authz, DomainEventPublisher publisher, Clock clock) {
        this.tx = tx; this.events = events; this.regs = regs; this.results = results; this.authz = authz; this.publisher = publisher;
    }

    /** Results are visible to all logged-in users once published, and to organizers before (FR-CMP-03). */
    public List<CompetitionResult> results(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            Event e = competition(eventId);
            if (!results.isPublished(eventId)) authz.requireOrganizer(s, e);
            return results.findByEvent(eventId);
        });
    }

    public boolean isPublished(UserSession s, long eventId) { authz.requireSession(s); return tx.inTransaction(() -> results.isPublished(eventId)); }

    /** Participants who can be placed: only Attended registrations (FR-CMP-04). */
    public List<RegistrantRow> placeableParticipants(UserSession s, long eventId) {
        authz.requireSession(s);
        return tx.inTransaction(() -> {
            authz.requireOrganizer(s, competition(eventId));
            return regs.findRegistrants(eventId, null).stream().filter(r -> r.registration().status() == RegistrationStatus.ATTENDED).toList();
        });
    }

    private Event competition(long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> new NotFoundException("error.event.notFound"));
        if (!(e instanceof Competition)) throw new ValidationException("error.results.notCompetition");
        return e;
    }

    private List<CompetitionResult> validate(long eventId, List<Entry> entries) {
        Set<Long> seen = new HashSet<>();
        Set<Long> attended = new HashSet<>();
        for (RegistrantRow r : regs.findRegistrants(eventId, null)) if (r.registration().status() == RegistrationStatus.ATTENDED) attended.add(r.registration().getUserId());
        List<CompetitionResult> list = new ArrayList<>();
        for (Entry en : entries) {
            if (en.placement() < 1 || en.placement() > 3) throw new ValidationException("error.results.placement");
            if (!seen.add(en.userId())) throw new ValidationException("error.results.duplicate");
            if (!attended.contains(en.userId())) throw new ValidationException("error.results.notAttended");
            if (en.score() != null && en.score().signum() < 0) throw new ValidationException("error.results.score");
            list.add(new CompetitionResult(eventId, en.userId(), en.placement(), en.score()));
        }
        return list;
    }

    /** Saves the results as a draft (replaces the draft). Published results are changed only by {@link #correct}. */
    public void saveDraft(UserSession s, long eventId, List<Entry> entries) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            competition(eventId);
            authz.requireOrganizer(s, e);
            if (results.isPublished(eventId)) throw new ConflictException("error.results.alreadyPublished");
            if (e.status() != EventStatus.ONGOING && e.status() != EventStatus.COMPLETED) throw new ConflictException("error.results.notYet");
            results.replaceAll(eventId, validate(eventId, entries));
        });
    }

    /** Publishes the draft; participants are notified (NT-13). */
    public void publish(UserSession s, long eventId) {
        authz.requireSession(s);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            competition(eventId);
            authz.requireOrganizer(s, e);
            if (results.isPublished(eventId)) throw new ConflictException("error.results.alreadyPublished");
            if (results.findByEvent(eventId).isEmpty()) throw new ValidationException("error.results.empty");
            results.setPublished(eventId, true);
            publisher.publish(new ResultsPublished(s.getUserId(), eventId, e.getTitle(), participants(eventId), false));
        });
    }

    /** Only Staff correct published results; the correction is logged and participants are notified again (FR-CMP-05). */
    public void correct(UserSession s, long eventId, List<Entry> entries) {
        authz.require(s, Permission.MANAGE_EVENTS);
        tx.runInTransaction(() -> {
            Event e = events.lockById(eventId);
            competition(eventId);
            if (!results.isPublished(eventId)) throw new ConflictException("error.results.notPublished");
            results.replaceAll(eventId, validate(eventId, entries));
            publisher.publish(new ResultsPublished(s.getUserId(), eventId, e.getTitle(), participants(eventId), true));
        });
    }

    private List<Long> participants(long eventId) { return regs.findUserIds(eventId, RegistrationStatus.CONFIRMED, RegistrationStatus.ATTENDED); }

    /** The caller's published placements (profile, FR-CMP-07). */
    public List<CompetitionResult> myPlacements(UserSession s) {
        authz.requireSession(s);
        return tx.inTransaction(() -> results.findByUser(s.getUserId()));
    }
}

// ======== service.search ========

/** Keyword search, filtering, sorting and pagination of events (UC-03, FR-SRC-01..05). */
class SearchService {
    private final TransactionManager tx;
    private final SearchRepository repo;
    private final AuthorizationService authz;
    private final Clock clock;

    public SearchService(TransactionManager tx, SearchRepository repo, AuthorizationService authz, Clock clock) {
        this.tx = tx; this.repo = repo; this.authz = authz; this.clock = clock;
    }

    /** Public list for guests (session null) or the browse list for logged-in users. */
    public Page<EventCard> search(UserSession s, SearchCriteria c) {
        if (s == null) { c.visibility = SearchCriteria.Visibility.PUBLIC; c.includePast = false; }
        else { authz.requireSession(s); c.visibility = SearchCriteria.Visibility.LOGGED_IN; }
        return run(c);
    }

    /** Management lists (Staff event list, pending proposals, club events): all states (FR-LCY-10). Officers must name their own club. */
    public Page<EventCard> manage(UserSession s, SearchCriteria c) {
        authz.requireSession(s);
        if (!s.getAccountType().basePermissions().contains(Permission.MANAGE_EVENTS)) {
            if (c.hostClubId == null) throw new AuthorizationException("error.forbidden");
            authz.require(s, Permission.PROPOSE_EVENT, c.hostClubId);
        }
        c.visibility = SearchCriteria.Visibility.MANAGEMENT;
        return run(c);
    }

    private Page<EventCard> run(SearchCriteria c) {
        c.pageSize = Math.min(Math.max(1, c.pageSize), 100);
        c.page = Math.max(0, c.page);
        return tx.inTransaction(() -> repo.search(c, clock.instant()));
    }
}

/**
 * Rule-based recommendations: a weighted sum of independent {@link RecommendationRule}s loaded from
 * recommendation.properties (UC-04, FR-RCM-01..06). Adding a rule needs a new class and one line in the file.
 */
class RecommendationEngine {
    private final TransactionManager tx;
    private final EventRepository events;
    private final RegistrationRepository regs;
    private final ClubRepository clubs;
    private final UserRepository users;
    private final CatalogRepository catalog;
    private final AuthorizationService authz;
    private final Clock clock;
    private final List<RecommendationRule> rules;
    private final Map<String, Double> weights;
    private final int limit;

    public RecommendationEngine(TransactionManager tx, EventRepository events, RegistrationRepository regs, ClubRepository clubs,
                                UserRepository users, CatalogRepository catalog, AuthorizationService authz, Clock clock, Properties config) {
        this.tx = tx; this.events = events; this.regs = regs; this.clubs = clubs; this.users = users; this.catalog = catalog; this.authz = authz; this.clock = clock;
        this.limit = Integer.parseInt(config.getProperty("limit", "10"));
        List<RecommendationRule> list = new ArrayList<>();
        Map<String, Double> w = new HashMap<>();
        for (String cls : config.getProperty("rules", "").split(",")) {
            if (cls.isBlank()) continue;
            try { list.add((RecommendationRule) Class.forName(cls.trim()).getDeclaredConstructor().newInstance()); }
            catch (ReflectiveOperationException | ClassCastException e) { throw new IllegalStateException("Cannot load recommendation rule " + cls, e); }
        }
        for (RecommendationRule r : list) w.put(r.weightKey(), Double.parseDouble(config.getProperty(r.weightKey(), "1.0")));
        this.rules = List.copyOf(list); this.weights = Map.copyOf(w);
    }

    /** Loads configuration from an external file when the path is given and exists, otherwise from the packaged default. */
    public static Properties loadConfig(Path external) {
        Properties p = new Properties();
        try {
            if (external != null && Files.exists(external)) { try (InputStream in = Files.newInputStream(external)) { p.load(in); } }
            else try (InputStream in = RecommendationEngine.class.getResourceAsStream("/recommendation.properties")) { p.load(in); }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return p;
    }

    /** Up to the configured number of upcoming Open events for the student, best first, each with a reason. */
    public List<Recommendation> recommend(UserSession s) {
        authz.require(s, Permission.RECOMMENDATIONS);
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            List<Event> candidates = events.findUpcomingOpen(now, 300);
            List<Event> registered = events.findActiveRegisteredBy(s.getUserId(), now);
            Set<Long> registeredIds = new HashSet<>();
            for (Event e : registered) registeredIds.add(e.getId());
            List<Event> eligible = new ArrayList<>();
            for (Event c : candidates) {
                if (registeredIds.contains(c.getId())) continue;                                               // FR-RCM-03
                boolean clash = false;
                for (Event r : registered) if (TimeSlot.firstOverlap(c.getTimeSlots(), r.getTimeSlots()).isPresent()) { clash = true; break; }
                if (!clash) eligible.add(c);
            }
            Set<Long> interests = users.findInterests(s.getUserId());
            Set<Long> myClubs = new HashSet<>(clubs.findActiveClubIds(s.getUserId()));
            Set<Long> attendedCats = regs.findAttendedCategoryIds(s.getUserId());
            RecommendationContext ctx = new RecommendationContext(s.getUserId(), interests, myClubs, attendedCats,
                    catalog.tagNames(), catalog.categoryNames(), clubs.findNames(myClubs), now);
            List<Recommendation> out = new ArrayList<>();
            if (ctx.isEmptyProfile()) {                                                                         // FR-RCM-05
                Map<Long, int[]> counts = regs.countsFor(eligible.stream().map(Event::getId).toList());
                eligible.sort(Comparator.comparingInt((Event e) -> -(counts.get(e.getId())[0] + counts.get(e.getId())[1])).thenComparing(Event::getStartAt));
                for (Event e : eligible) { if (out.size() >= limit) break; out.add(new Recommendation(e, 0, new Reason("reason.popular", null))); }
                return out;
            }
            for (Event e : eligible) {
                double total = 0, best = 0; Reason reason = null;
                for (RecommendationRule r : rules) {
                    RuleScore rs = r.score(ctx, e);
                    double part = rs.value() * weights.get(r.weightKey());
                    total += part;
                    if (part > best && rs.reason() != null) { best = part; reason = rs.reason(); }
                }
                if (total > 0) out.add(new Recommendation(e, total, reason));
            }
            out.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparing(r -> r.event().getStartAt()));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        });
    }
}
