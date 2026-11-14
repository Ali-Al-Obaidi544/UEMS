package uems;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;



/**
 * Composition root: builds the persistence layer, the services and the observers, and wires them together.
 * The user interface and the tools get everything from here; no other class creates services.
 */
public final class AppContext implements AutoCloseable {
    public final Clock clock;
    public final Persistence persistence;
    public final DomainEventPublisher publisher = new DomainEventPublisher();
    public final PasswordHasher hasher;
    public final SessionManager sessions;
    public final AuthorizationService authz;
    public final AuthService auth;
    public final UserService users;
    public final VenueService venues;
    public final NotificationService notifications;
    public final AuditService audit;
    public final SettingsService settings;
    public final CatalogService catalog;
    public final EventService events;
    public final WorkshopService workshops;
    public final RegistrationFacade registrations;
    public final CheckInService checkIn;
    public final ClubService clubs;
    public final ResultService results;
    public final FeedbackService feedback;
    public final DiscussionService discussion;
    public final SearchService search;
    public final RecommendationEngine recommendations;
    public final CalendarExportService calendar;
    public final SchedulerService scheduler;

    /** Builds the whole application from configuration. */
    public static AppContext create(Config cfg) {
        return new AppContext(cfg, Clock.systemUTC());
    }

    /** Builds with a given clock (tests). */
    public AppContext(Config cfg, Clock clock) {
        this.clock = clock;
        persistence = new Persistence(cfg.require("db.url"), cfg.require("db.user"), cfg.get("db.password", ""), cfg.getInt("db.pool.size", 5));
        hasher = new PasswordHasher(cfg.getInt("security.pbkdf2.iterations", PasswordHasher.DEFAULT_ITERATIONS));
        Persistence p = persistence;
        sessions = new SessionManager(clock, Duration.ofMinutes(20));
        authz = new AuthorizationService(sessions, p.clubs, p.tx);
        notifications = new NotificationService(p.tx, p.notifications, sessions, clock);
        audit = new AuditService(p.tx, p.audit, authz, clock);
        publisher.subscribe(DomainEvent.class, notifications);
        publisher.subscribe(DomainEvent.class, audit);
        auth = new AuthService(p.tx, p.users, p.catalog, sessions, hasher, publisher, clock);
        users = new UserService(p.tx, p.users, p.clubs, authz, sessions, hasher, publisher);
        venues = new VenueService(p.tx, p.venues, p.events, authz, clock);
        settings = new SettingsService(p.tx, p.catalog, authz, sessions, publisher);
        catalog = new CatalogService(p.tx, p.catalog, authz);
        IntervalClashStrategy clash = new IntervalClashStrategy(p.events);
        WaitlistService waitlist = new WaitlistService(p.registrations, p.users, clash, publisher, clock);
        events = new EventService(p.tx, p.events, p.registrations, p.clubs, p.users, venues, waitlist, authz, publisher, clock);
        workshops = new WorkshopService(p.tx, p.events, events);
        registrations = new RegistrationFacade(p.tx, p.events, p.registrations, p.users, clash, waitlist, authz, publisher, clock);
        checkIn = new CheckInService(p.tx, p.events, p.registrations, authz, publisher, clock);
        clubs = new ClubService(p.tx, p.clubs, p.users, p.events, authz, publisher, clock);
        results = new ResultService(p.tx, p.events, p.registrations, p.results, authz, publisher, clock);
        feedback = new FeedbackService(p.tx, p.events, p.registrations, p.social, authz, clock);
        discussion = new DiscussionService(p.tx, p.events, p.social, p.users, authz, publisher, clock);
        search = new SearchService(p.tx, p.search, authz, clock);
        String recPath = cfg.get("recommendation.config", "");
        Properties rec = RecommendationEngine.loadConfig(recPath.isBlank() ? null : Path.of(recPath));
        recommendations = new RecommendationEngine(p.tx, p.events, p.registrations, p.clubs, p.users, p.catalog, authz, clock, rec);
        calendar = new CalendarExportService(p.tx, p.events, p.registrations, p.venues, authz, clock);
        scheduler = new SchedulerService(p.tx, p.scheduler, p.events, p.registrations, p.notifications, p.catalog, publisher, clock);
    }

    @Override public void close() { persistence.close(); }
}
