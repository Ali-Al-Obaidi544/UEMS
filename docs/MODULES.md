# Module ownership

The source code is organized **by module**: every module has one *logic* file (domain classes, repositories and services) and one *screens* file (Swing views). All files are in the Java package `uems`; each file contains many separate classes (see `CLASS_INDEX.md`).

| # | Module | Files | Lines | Share |
|---|---|---|---|---|
| 1 | Users, security and infrastructure | `UserLogic.java`, `UserScreens.java`, `AppContext.java`, `UemsApp.java` | 2079 | 26% |
| 2 | Events and lifecycle | `EventLogic.java`, `EventScreens.java` | 2006 | 25% |
| 3 | Registration and attendance | `RegistrationLogic.java`, `RegistrationScreens.java`, `UiKit.java`, `DbSetup.java` | 1988 | 25% |
| 4 | Clubs, workshops, competitions and discovery | `ClubLogic.java`, `ClubScreens.java` | 1843 | 23% |
| | **Total** | 12 files | **7916** | |

Shares are measured in lines (including comments and blank lines) and are only a rough measure of size: they do not capture how hard the rules in a file are.

## What is in each file

### Module 1: Users, security and infrastructure

**`UserLogic.java`** (1631 lines, 68 classes)

- *Infrastructure*: `ArabicNormalizer`, `Config`, `PasswordHash`, `PasswordHasher`, `SecureTokens`
- *Domain*: `AccountLockedException`, `AlreadyRegisteredException`, `AuthorizationException`, `CancellationClosedException`, `ConflictException`, `DataAccessException`, `Gst`, `IllegalTransitionException`, `InvalidCredentialsException`, `Language`, `LocalizedText`, `NotFoundException`, `NotOpenException`, `Page`, `Schedulable`, `ScheduleClashException`, `SessionExpiredException`, `StaleDataException`, `TimeSlot`, `UemsException`, `ValidationException`, `VenueConflictException`, `AccountType`, `Administrator`, `Permission`, `StaffMember`, `Student`, `User`, `UserSession`, `UserStatus`, `Booking`, `Venue`, `AccountChanged`, `AccountLocked`, `AccountTypeChanged`, `DomainEvent`, `DomainEventListener`, `DomainEventPublisher`, `LoginFailed`, `SettingChanged`, `UserLoggedIn`, `AuditRepository`, `TransactionManager`, `UserRepository`, `VenueRepository`
- *Persistence*: `ConnectionPool`, `Jdbc`, `JdbcAuditRepository`, `JdbcTransactionManager`, `JdbcUserRepository`, `JdbcVenueRepository`, `PostgresDialect`, `SqlDialect`, `Persistence`
- *Services*: `AuthService`, `AuthorizationService`, `PasswordPolicy`, `SessionManager`, `UserData`, `UserService`, `VenueService`, `AuditService`, `SettingsService`

**`UserScreens.java`** (327 lines, 5 classes)

- `LoginScreen`, `ProfileScreen`, `UsersScreen`, `VenuesScreen`, `AuditScreen`

**`AppContext.java`** (82 lines, 1 classes)

- `AppContext`

**`UemsApp.java`** (39 lines, 1 classes)

- *Ui*: `UemsApp`

### Module 2: Events and lifecycle

**`EventLogic.java`** (1534 lines, 40 classes)

- *Domain*: `AbstractEventState`, `CancelledState`, `ClosedState`, `Competition`, `CompletedState`, `DraftState`, `Event`, `EventData`, `EventFactory`, `EventSeries`, `EventState`, `EventStates`, `EventStatus`, `EventType`, `GeneralEvent`, `OngoingState`, `OpenState`, `PendingApprovalState`, `PublishedState`, `RecurrenceRule`, `SessionData`, `Workshop`, `WorkshopSession`, `EventCancelled`, `EventChanged`, `EventReminder`, `ProposalDecided`, `ProposalSubmitted`, `CatalogRepository`, `EventRepository`, `SchedulerRepository`
- *Persistence*: `JdbcCatalogRepository`, `JdbcEventRepository`, `JdbcSchedulerRepository`
- *Services*: `CatalogService`, `EventDashboard`, `EventService`, `SeriesOccurrence`, `SeriesScope`, `SchedulerService`

**`EventScreens.java`** (472 lines, 3 classes)

- `EventDetailScreen`, `EventEditorScreen`, `ManageEventsScreen`

### Module 3: Registration and attendance

**`RegistrationLogic.java`** (856 lines, 31 classes)

- *Domain*: `Clash`, `ClashDetectionStrategy`, `MyRegistration`, `RegistrantRow`, `Registration`, `RegistrationState`, `RegistrationStates`, `RegistrationStatus`, `AuditEntry`, `Notification`, `NotificationSpec`, `Params`, `CheckInChanged`, `PromotionSkipped`, `RegistrationCancelled`, `RegistrationConfirmed`, `RegistrationWaitlisted`, `WaitlistPromoted`, `NotificationRepository`, `RegistrationRepository`
- *Persistence*: `JdbcNotificationRepository`, `JdbcRegistrationRepository`
- *Services*: `NotificationService`, `AttendanceView`, `CheckInService`, `IntervalClashStrategy`, `RegistrationFacade`, `SeatInfo`, `WaitlistService`, `IcsBuilder`, `CalendarExportService`

**`RegistrationScreens.java`** (269 lines, 3 classes)

- `NotificationsScreen`, `MyRegistrationsScreen`, `EventDialogs`

**`UiKit.java`** (647 lines, 7 classes)

- *Ui*: `Messages`, `Fmt`, `UiContext`, `Screen`, `DataTable`, `Ui`, `MainFrame`

**`DbSetup.java`** (216 lines, 2 classes)

- *Tools*: `DbSetup`, `DemoData`

### Module 4: Clubs, workshops, competitions and discovery

**`ClubLogic.java`** (1404 lines, 45 classes)

- *Domain*: `Club`, `ClubMembership`, `MemberRow`, `MembershipRole`, `MembershipStatus`, `CompetitionResult`, `Comment`, `Feedback`, `FeedbackSummary`, `AnnouncementSent`, `CommentRemoved`, `CommentReplied`, `FeedbackRequested`, `JoinRequestDecided`, `JoinRequestSubmitted`, `OfficerChanged`, `ResultsPublished`, `ClubRule`, `HistoryRule`, `InterestRule`, `Reason`, `Recommendation`, `RecommendationContext`, `RecommendationRule`, `RuleScore`, `SoonRule`, `EventCard`, `SearchCriteria`, `ClubRepository`, `ResultRepository`, `SearchRepository`, `SocialRepository`
- *Persistence*: `JdbcClubRepository`, `JdbcResultRepository`, `JdbcSearchRepository`, `JdbcSocialRepository`
- *Services*: `WorkshopService`, `ClubDetails`, `ClubInfo`, `ClubService`, `DiscussionService`, `FeedbackService`, `ResultService`, `SearchService`, `RecommendationEngine`

**`ClubScreens.java`** (439 lines, 7 classes)

- `BrowseScreen`, `HomeScreen`, `ClubsScreen`, `ClubManageScreen`, `ClubsAdminScreen`, `DiscussionPanel`, `FeedbackPanel`

## Shared files and rules for working together

- `UiKit.java` (messages, formats, `UiContext`, `Screen`, `DataTable`, form builders, `MainFrame`) is used by every screen. Change it only after telling the others.
- `UserLogic.java` also contains the shared foundation used by all modules: the exceptions, `TimeSlot`, `LocalizedText`, the JDBC helper, the transaction manager and the connection pool.
- `AppContext.java` wires all services together; adding a service means one line there.
- Texts of the interface are in `tools/messages.src` (English and Arabic); several members may add lines at the end of that file.
- A class may call classes of other modules (for example registration uses the event repository); this is normal because they share one package.

## Layout note

The layout only organizes the code by area of responsibility, so that each member can work in their own files. It says nothing about who wrote the code; the Final Report (section 1.4) states how the code was produced.
