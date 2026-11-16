package uems;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

// MODULE 4 - CLUBS, WORKSHOPS, COMPETITIONS AND DISCOVERY (logic)
// Contains the domain classes for clubs, workshops' results, feedback, discussion, search and recommendations, their repositories, and the services for clubs, workshop sessions, results, feedback, discussion, search and recommendations.
// Layers in this file, in order: INFRASTRUCTURE (module 1 only), DOMAIN, PERSISTENCE, SERVICES.

// ========== DOMAIN ==========

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

/** NT-15: announcement by an organizer to registrants or club members. */
record AnnouncementSent(String link, LocalizedText subject, LocalizedText message, List<Long> recipients) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        List<NotificationSpec> l = new ArrayList<>();
        for (long u : recipients) l.add(spec(u, "NT-15", "notif.announcement", link, Params.text(subject), Params.text(message)));
        return l;
    }
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

/** NT-12: officer appointed or removed; audited. */
record OfficerChanged(long actorId, long clubId, LocalizedText club, long userId, boolean appointed) implements DomainEvent {
    @Override public List<NotificationSpec> notifications() {
        return List.of(spec(userId, "NT-12", appointed ? "notif.officer.appointed" : "notif.officer.removed", "club:" + clubId, Params.text(club)));
    }
    @Override public AuditSpec audit() { return new AuditSpec(actorId, appointed ? "OFFICER_APPOINT" : "OFFICER_REMOVE", "club:" + clubId + " user:" + userId, null); }
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

// ========== PERSISTENCE ==========

/** JDBC implementation of {@link ClubRepository}. */
class JdbcClubRepository implements ClubRepository {
    private static final String CLUB = "club_id, name_en, name_ar, description_en, description_ar, category_id, active, version";
    private static final String MEM = "m.membership_id, m.club_id, m.user_id, m.role, m.status, m.requested_at, m.decided_at, m.joined_at, m.reason";
    private final Jdbc db;
    public JdbcClubRepository(Jdbc db) { this.db = db; }

    private static Club mapClub(ResultSet rs) throws SQLException {
        Club c = new Club();
        c.setId(rs.getLong("club_id"));
        c.setName(new LocalizedText(rs.getString("name_en"), rs.getString("name_ar")));
        c.setDescription(new LocalizedText(rs.getString("description_en"), rs.getString("description_ar")));
        c.setCategoryId(Jdbc.longOrNull(rs, "category_id"));
        c.setActive(rs.getBoolean("active")); c.setVersion(rs.getInt("version"));
        return c;
    }
    private static ClubMembership mapMembership(ResultSet rs) throws SQLException {
        ClubMembership m = new ClubMembership();
        m.setId(rs.getLong("membership_id")); m.setClubId(rs.getLong("club_id")); m.setUserId(rs.getLong("user_id"));
        m.setRole(MembershipRole.valueOf(rs.getString("role"))); m.setStatus(MembershipStatus.valueOf(rs.getString("status")));
        m.setRequestedAt(Jdbc.instant(rs, "requested_at")); m.setDecidedAt(Jdbc.instant(rs, "decided_at"));
        m.setJoinedAt(Jdbc.instant(rs, "joined_at")); m.setReason(rs.getString("reason"));
        return m;
    }
    private static MemberRow mapRow(ResultSet rs) throws SQLException {
        return new MemberRow(mapMembership(rs), rs.getString("university_id"), new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")));
    }
    private static final String ROW = "SELECT " + MEM + ", u.university_id, u.full_name_en, u.full_name_ar FROM club_membership m JOIN app_user u ON u.user_id=m.user_id ";

    @Override public Optional<Club> findById(long id) { return db.one("SELECT " + CLUB + " FROM club WHERE club_id=?", JdbcClubRepository::mapClub, id); }
    @Override public Club lockById(long id) {
        return db.one("SELECT " + CLUB + " FROM club WHERE club_id=?" + db.dialect().forUpdate(), JdbcClubRepository::mapClub, id).orElseThrow(() -> new NotFoundException("error.club.notFound"));
    }
    @Override public List<Club> findAll(boolean activeOnly) { return db.list("SELECT " + CLUB + " FROM club" + (activeOnly ? " WHERE active" : "") + " ORDER BY name_en", JdbcClubRepository::mapClub); }
    @Override public Club save(Club c) {
        String ad = c.getDescription().ar().isEmpty() ? null : c.getDescription().ar();
        String an = c.getName().ar().isEmpty() ? null : c.getName().ar();
        if (c.getId() == 0) {
            c.setId(db.insert("INSERT INTO club (name_en, name_ar, description_en, description_ar, category_id, active) VALUES (?,?,?,?,?,?) RETURNING club_id",
                    c.getName().en(), an, c.getDescription().en(), ad, c.getCategoryId(), c.isActive()));
        } else {
            int n = db.exec("UPDATE club SET name_en=?, name_ar=?, description_en=?, description_ar=?, category_id=?, active=?, version=version+1 WHERE club_id=? AND version=?",
                    c.getName().en(), an, c.getDescription().en(), ad, c.getCategoryId(), c.isActive(), c.getId(), c.getVersion());
            if (n == 0) throw new StaleDataException("error.stale");
            c.setVersion(c.getVersion() + 1);
        }
        return c;
    }
    @Override public Map<Long, LocalizedText> findNames(Collection<Long> ids) {
        Map<Long, LocalizedText> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        db.list("SELECT club_id, name_en, name_ar FROM club WHERE club_id = ANY(?)", rs -> m.put(rs.getLong(1), new LocalizedText(rs.getString(2), rs.getString(3))), ids);
        return m;
    }
    @Override public Optional<ClubMembership> findOpenMembership(long clubId, long userId) {
        return db.one("SELECT " + MEM + " FROM club_membership m WHERE m.club_id=? AND m.user_id=? AND m.status IN ('PENDING','ACTIVE')", JdbcClubRepository::mapMembership, clubId, userId);
    }
    @Override public Optional<ClubMembership> findMembership(long id) { return db.one("SELECT " + MEM + " FROM club_membership m WHERE m.membership_id=?", JdbcClubRepository::mapMembership, id); }
    @Override public ClubMembership saveMembership(ClubMembership m) {
        if (m.getId() == 0) {
            m.setId(db.insert("INSERT INTO club_membership (club_id, user_id, role, status, requested_at, decided_at, joined_at, reason) VALUES (?,?,?,?,?,?,?,?) RETURNING membership_id",
                    m.getClubId(), m.getUserId(), m.getRole(), m.getStatus(), m.getRequestedAt(), m.getDecidedAt(), m.getJoinedAt(), m.getReason()));
        } else {
            db.exec("UPDATE club_membership SET role=?, status=?, decided_at=?, joined_at=?, reason=? WHERE membership_id=?",
                    m.getRole(), m.getStatus(), m.getDecidedAt(), m.getJoinedAt(), m.getReason(), m.getId());
        }
        return m;
    }
    @Override public List<MemberRow> findMembers(long clubId, String search) {
        StringBuilder sql = new StringBuilder(ROW + "WHERE m.club_id=? AND m.status='ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(clubId));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (u.university_id").append(db.dialect().ilike()).append("? OR u.full_name_en").append(db.dialect().ilike())
               .append("? OR COALESCE(u.full_name_ar,'')").append(db.dialect().ilike()).append("?)");
            args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY m.role DESC, m.joined_at");
        return db.list(sql.toString(), JdbcClubRepository::mapRow, args.toArray());
    }
    @Override public List<MemberRow> findPending(long clubId) { return db.list(ROW + "WHERE m.club_id=? AND m.status='PENDING' ORDER BY m.requested_at", JdbcClubRepository::mapRow, clubId); }
    @Override public List<MemberRow> findOfficers(long clubId) { return db.list(ROW + "WHERE m.club_id=? AND m.status='ACTIVE' AND m.role='OFFICER' ORDER BY m.joined_at", JdbcClubRepository::mapRow, clubId); }
    @Override public int countOfficers(long clubId) { return (int) db.count("SELECT count(*) FROM club_membership WHERE club_id=? AND status='ACTIVE' AND role='OFFICER'", clubId); }
    @Override public int countMembers(long clubId) { return (int) db.count("SELECT count(*) FROM club_membership WHERE club_id=? AND status='ACTIVE'", clubId); }
    @Override public boolean isActiveOfficer(long userId, long clubId) {
        return db.count("SELECT count(*) FROM club_membership WHERE club_id=? AND user_id=? AND status='ACTIVE' AND role='OFFICER'", clubId, userId) > 0;
    }
    @Override public List<Long> findOfficerClubIds(long userId) {
        return db.list("SELECT m.club_id FROM club_membership m JOIN club c ON c.club_id=m.club_id WHERE m.user_id=? AND m.status='ACTIVE' AND m.role='OFFICER' AND c.active", rs -> rs.getLong(1), userId);
    }
    @Override public List<Long> findActiveClubIds(long userId) { return db.list("SELECT club_id FROM club_membership WHERE user_id=? AND status='ACTIVE'", rs -> rs.getLong(1), userId); }
    @Override public boolean holdsOfficerPosition(long userId) {
        return db.count("SELECT count(*) FROM club_membership WHERE user_id=? AND status='ACTIVE' AND role='OFFICER'", userId) > 0;
    }
    @Override public List<Long> findOfficerUserIds(long clubId) { return db.list("SELECT user_id FROM club_membership WHERE club_id=? AND status='ACTIVE' AND role='OFFICER'", rs -> rs.getLong(1), clubId); }
    @Override public List<Long> findMemberUserIds(long clubId) { return db.list("SELECT user_id FROM club_membership WHERE club_id=? AND status='ACTIVE'", rs -> rs.getLong(1), clubId); }
}

/** JDBC implementation of {@link ResultRepository}. */
class JdbcResultRepository implements ResultRepository {
    private final Jdbc db;
    public JdbcResultRepository(Jdbc db) { this.db = db; }

    @Override public List<CompetitionResult> findByEvent(long eventId) {
        return db.list("SELECT r.result_id, r.event_id, r.user_id, r.placement, r.score, u.university_id, u.full_name_en, u.full_name_ar FROM competition_result r "
                + "JOIN app_user u ON u.user_id=r.user_id WHERE r.event_id=? ORDER BY r.placement, u.full_name_en", rs -> {
            CompetitionResult c = new CompetitionResult(rs.getLong("event_id"), rs.getLong("user_id"), rs.getInt("placement"), rs.getBigDecimal("score"));
            c.setId(rs.getLong("result_id")); c.setUniversityId(rs.getString("university_id"));
            c.setName(new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")));
            return c;
        }, eventId);
    }
    @Override public void replaceAll(long eventId, List<CompetitionResult> results) {
        db.exec("DELETE FROM competition_result WHERE event_id=?", eventId);
        for (CompetitionResult r : results)
            db.exec("INSERT INTO competition_result (event_id, user_id, placement, score) VALUES (?,?,?,?)", eventId, r.getUserId(), r.getPlacement(), r.getScore());
    }
    @Override public void setPublished(long eventId, boolean published) { db.exec("UPDATE event SET results_published=? WHERE event_id=?", published, eventId); }
    @Override public boolean isPublished(long eventId) { return db.count("SELECT count(*) FROM event WHERE event_id=? AND results_published", eventId) > 0; }
    @Override public List<CompetitionResult> findByUser(long userId) {
        return db.list("SELECT r.result_id, r.event_id, r.user_id, r.placement, r.score, e.title_en, e.title_ar FROM competition_result r "
                + "JOIN event e ON e.event_id=r.event_id WHERE r.user_id=? AND e.results_published ORDER BY e.start_at DESC", rs -> {
            CompetitionResult c = new CompetitionResult(rs.getLong("event_id"), rs.getLong("user_id"), rs.getInt("placement"), rs.getBigDecimal("score"));
            c.setId(rs.getLong("result_id")); c.setName(new LocalizedText(rs.getString("title_en"), rs.getString("title_ar")));
            return c;
        }, userId);
    }
}

/** JDBC implementation of {@link SearchRepository}. SQL is assembled from fixed fragments; every value is a bound parameter. */
class JdbcSearchRepository implements SearchRepository {
    private final Jdbc db;
    private final JdbcEventRepository events;
    public JdbcSearchRepository(Jdbc db, JdbcEventRepository events) { this.db = db; this.events = events; }

    @Override public Page<EventCard> search(SearchCriteria c, Instant now) {
        String from = " FROM event e LEFT JOIN (SELECT event_id, count(*) FILTER (WHERE status IN ('CONFIRMED','ATTENDED')) AS seats, "
                + "count(*) FILTER (WHERE status='WAITLISTED') AS wl FROM registration GROUP BY event_id) rc ON rc.event_id=e.event_id "
                + "LEFT JOIN (SELECT event_id, avg(rating) AS av, count(*) AS cnt FROM feedback GROUP BY event_id) fb ON fb.event_id=e.event_id WHERE 1=1";
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        switch (c.visibility) {
            case PUBLIC -> where.append(" AND e.state IN ('PUBLISHED','OPEN','CLOSED','ONGOING')");
            case LOGGED_IN -> where.append(" AND e.state IN ('PUBLISHED','OPEN','CLOSED','ONGOING','COMPLETED','CANCELLED')");
            case MANAGEMENT -> { }
        }
        if (!c.includePast) { where.append(" AND (e.end_at IS NULL OR e.end_at>=?)"); args.add(now); }
        if (c.keyword != null && !c.keyword.isBlank()) {
            for (String w : ArabicNormalizer.normalize(c.keyword).split("\\s+")) {
                if (w.isEmpty()) continue;
                where.append(" AND e.search_text LIKE ? ESCAPE '\\'");
                args.add("%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
        }
        if (c.type != null) { where.append(" AND e.event_type=?"); args.add(c.type); }
        if (c.categoryId != null) { where.append(" AND e.category_id=?"); args.add(c.categoryId); }
        if (c.from != null) { where.append(" AND e.start_at>=?"); args.add(c.from); }
        if (c.to != null) { where.append(" AND e.start_at<=?"); args.add(c.to); }
        if (c.venueId != null) { where.append(" AND e.venue_id=?"); args.add(c.venueId); }
        if (c.hostClubId != null) { where.append(" AND e.host_club_id=?"); args.add(c.hostClubId); }
        if (c.openOnly) where.append(" AND e.state='OPEN'");
        if (c.seatsAvailableOnly) where.append(" AND e.capacity>COALESCE(rc.seats,0)");
        if (c.statuses != null && !c.statuses.isEmpty()) {
            List<String> names = new ArrayList<>();
            c.statuses.forEach(s -> names.add(s.name()));
            where.append(" AND e.state = ANY(?)"); args.add(names);
        }
        String order = switch (c.sort) {
            case TITLE -> " ORDER BY lower(COALESCE(NULLIF(e.title_en,''), e.title_ar)), e.event_id";
            case POPULARITY -> " ORDER BY (COALESCE(rc.seats,0)+COALESCE(rc.wl,0)) DESC, e.start_at, e.event_id";
            default -> " ORDER BY e.start_at ASC NULLS LAST, e.event_id";
        };
        long total = db.count("SELECT count(*)" + from + where, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(c.pageSize); pageArgs.add((long) c.page * c.pageSize);
        List<Object[]> rows = db.list("SELECT " + JdbcEventRepository.COLS + ", COALESCE(rc.seats,0) AS seats, COALESCE(rc.wl,0) AS wl, COALESCE(fb.av,0) AS av, COALESCE(fb.cnt,0) AS cnt"
                + from + where + order + " LIMIT ? OFFSET ?", rs -> new Object[]{JdbcEventRepository.mapRow(rs), rs.getInt("seats"), rs.getInt("wl"), rs.getDouble("av"), rs.getInt("cnt")},
                pageArgs.toArray());
        List<Event> evs = new ArrayList<>();
        for (Object[] r : rows) evs.add((Event) r[0]);
        events.hydrate(evs);
        List<EventCard> cards = new ArrayList<>();
        for (Object[] r : rows) cards.add(new EventCard((Event) r[0], (Integer) r[1], (Integer) r[2], (Double) r[3], (Integer) r[4]));
        return new Page<>(cards, c.page, c.pageSize, total);
    }
}

/** JDBC implementation of {@link SocialRepository}. */
class JdbcSocialRepository implements SocialRepository {
    private final Jdbc db;
    public JdbcSocialRepository(Jdbc db) { this.db = db; }

    private static Feedback mapFeedback(ResultSet rs) throws SQLException {
        Feedback f = new Feedback();
        f.setId(rs.getLong("feedback_id")); f.setEventId(rs.getLong("event_id")); f.setUserId(rs.getLong("user_id"));
        f.setRating(rs.getInt("rating")); f.setComment(rs.getString("comment"));
        f.setCreatedAt(Jdbc.instant(rs, "created_at")); f.setUpdatedAt(Jdbc.instant(rs, "updated_at"));
        return f;
    }
    @Override public Optional<Feedback> findFeedback(long eventId, long userId) {
        return db.one("SELECT feedback_id, event_id, user_id, rating, comment, created_at, updated_at FROM feedback WHERE event_id=? AND user_id=?", JdbcSocialRepository::mapFeedback, eventId, userId);
    }
    @Override public Feedback saveFeedback(Feedback f) {
        if (f.getId() == 0) {
            f.setId(db.insert("INSERT INTO feedback (event_id, user_id, rating, comment, created_at, updated_at) VALUES (?,?,?,?,?,?) RETURNING feedback_id",
                    f.getEventId(), f.getUserId(), f.getRating(), f.getComment(), f.getCreatedAt(), f.getUpdatedAt()));
        } else {
            db.exec("UPDATE feedback SET rating=?, comment=?, updated_at=? WHERE feedback_id=?", f.getRating(), f.getComment(), f.getUpdatedAt(), f.getId());
        }
        return f;
    }
    @Override public List<Feedback> findFeedbackByEvent(long eventId) {
        return db.list("SELECT f.feedback_id, f.event_id, f.user_id, f.rating, f.comment, f.created_at, f.updated_at, u.full_name_en, u.full_name_ar FROM feedback f "
                + "JOIN app_user u ON u.user_id=f.user_id WHERE f.event_id=? ORDER BY f.created_at DESC", rs -> {
            Feedback f = mapFeedback(rs);
            f.setAuthorName(new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")));
            return f;
        }, eventId);
    }

    private static Comment mapComment(ResultSet rs) throws SQLException {
        Comment c = new Comment();
        c.setId(rs.getLong("comment_id")); c.setEventId(rs.getLong("event_id")); c.setUserId(rs.getLong("user_id"));
        c.setParentId(Jdbc.longOrNull(rs, "parent_id")); c.setBody(rs.getString("body")); c.setCreatedAt(Jdbc.instant(rs, "created_at"));
        c.setEdited(rs.getBoolean("edited")); c.setRemoved(rs.getBoolean("removed")); c.setRemovedReason(rs.getString("removed_reason"));
        return c;
    }
    @Override public Comment saveComment(Comment c) {
        if (c.getId() == 0) {
            c.setId(db.insert("INSERT INTO discussion_comment (event_id, user_id, parent_id, body, created_at) VALUES (?,?,?,?,?) RETURNING comment_id",
                    c.getEventId(), c.getUserId(), c.getParentId(), c.getBody(), c.getCreatedAt()));
        } else {
            db.exec("UPDATE discussion_comment SET body=?, edited=?, removed=?, removed_reason=? WHERE comment_id=?", c.getBody(), c.isEdited(), c.isRemoved(), c.getRemovedReason(), c.getId());
        }
        return c;
    }
    @Override public Optional<Comment> findComment(long id) {
        return db.one("SELECT comment_id, event_id, user_id, parent_id, body, created_at, edited, removed, removed_reason FROM discussion_comment WHERE comment_id=?", JdbcSocialRepository::mapComment, id);
    }
    @Override public List<Comment> findComments(long eventId) {
        return db.list("SELECT c.comment_id, c.event_id, c.user_id, c.parent_id, c.body, c.created_at, c.edited, c.removed, c.removed_reason, u.full_name_en, u.full_name_ar, u.account_type, "
                + "EXISTS (SELECT 1 FROM event e JOIN club_membership m ON m.club_id=e.host_club_id WHERE e.event_id=c.event_id AND m.user_id=c.user_id AND m.status='ACTIVE' AND m.role='OFFICER') AS is_officer "
                + "FROM discussion_comment c JOIN app_user u ON u.user_id=c.user_id WHERE c.event_id=? ORDER BY c.created_at, c.comment_id", rs -> {
            Comment c = mapComment(rs);
            c.setAuthorName(new LocalizedText(rs.getString("full_name_en"), rs.getString("full_name_ar")));
            c.setAuthorType(AccountType.valueOf(rs.getString("account_type")));
            c.setAuthorIsOfficer(rs.getBoolean("is_officer"));
            return c;
        }, eventId);
    }
}

// ========== SERVICES ==========

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
