package uems;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// PERSISTENCE LAYER: JDBC connection pool, transaction manager and the repository implementations (PostgreSQL).

// ======== persistence.jdbc ========

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
