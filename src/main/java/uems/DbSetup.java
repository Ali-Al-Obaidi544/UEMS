package uems;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

// MODULE 3 - database setup tool (schema, reference data, first administrator) and the demo data loader.

// ======== tools ========

/**
 * Command-line tool that creates the UEMS schema, loads reference data and the initial Admin, and optionally demo data.
 * Usage: {@code java -cp ... uems.DbSetup [--config uems.properties] [--reset] [--demo]}
 */
public final class DbSetup {
    public static final String INITIAL_ADMIN_ID = "admin";
    public static final String INITIAL_ADMIN_PASSWORD = "ChangeMe123";

    private DbSetup() {}

    public static void main(String[] args) throws Exception {
        Path cfgPath = Path.of("uems.properties");
        boolean reset = false, demo = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> cfgPath = Path.of(args[++i]);
                case "--reset" -> reset = true;
                case "--demo" -> demo = true;
                default -> { System.err.println("Unknown option " + args[i]); System.exit(2); }
            }
        }
        Config cfg = Config.load(cfgPath);
        setup(cfg, reset, demo);
    }

    /** Creates (and with reset, first drops) everything. */
    public static void setup(Config cfg, boolean reset, boolean demo) throws Exception {
        try (Connection c = DriverManager.getConnection(cfg.require("db.url"), cfg.require("db.user"), cfg.get("db.password", ""))) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                if (reset) { st.execute("DROP SCHEMA public CASCADE"); st.execute("CREATE SCHEMA public"); }
                else {
                    try (ResultSet rs = st.executeQuery("SELECT to_regclass('public.app_user')")) {
                        if (rs.next() && rs.getString(1) != null) { System.out.println("Schema already exists. Use --reset to recreate it."); return; }
                    }
                }
                runScript(st, "/db/schema.sql");
                runScript(st, "/db/seed.sql");
            }
        }
        try (AppContext ctx = new AppContext(cfg, java.time.Clock.systemUTC())) {
            Administrator a = new Administrator();
            a.setUniversityId(INITIAL_ADMIN_ID); a.setFullName(new LocalizedText("System Administrator", "مدير النظام"));
            a.setEmail("admin@university.example");
            a.setPasswordHash(ctx.hasher.hash(INITIAL_ADMIN_PASSWORD.toCharArray())); a.setMustChangePassword(true);
            ctx.persistence.tx.runInTransaction(() -> ctx.persistence.users.save(a));
            System.out.println("Created Admin '" + INITIAL_ADMIN_ID + "' with password '" + INITIAL_ADMIN_PASSWORD + "' (must be changed at first login).");
            if (demo) { DemoData.load(ctx); System.out.println("Demo data loaded. See README for the demo accounts."); }
        }
        System.out.println("Database ready.");
    }

    private static void runScript(Statement st, String resource) throws IOException, SQLException {
        String sql;
        try (InputStream in = DbSetup.class.getResourceAsStream(resource)) { sql = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) if (!line.trim().startsWith("--")) clean.append(line).append('\n');
        for (String stmt : clean.toString().split(";")) if (!stmt.isBlank()) st.execute(stmt);
    }
}

/**
 * Loads demo data: 3 Staff, 20 Students, 5 venues, 3 clubs and a spread of events in different states (Design 2.3).
 * Demo students are numbered 202610001 to 202610020. All demo accounts use the password {@value #PASSWORD}.
 */
final class DemoData {
    static final String PASSWORD = "Demo1234";
    private static final String[] EN = {"Ahmed Al Farsi", "Fatima Al Hashimi", "Khalid Al Nuaimi", "Mariam Al Suwaidi", "Yousef Al Mazrouei", "Noura Al Ketbi",
            "Saeed Al Dhaheri", "Aisha Al Shamsi", "Hamad Al Blooshi", "Latifa Al Mheiri", "Rashid Al Ameri", "Salma Al Zaabi", "Omar Al Kaabi",
            "Hessa Al Marri", "Majid Al Falasi", "Shamma Al Qubaisi", "Sultan Al Tayer", "Maitha Al Romaithi", "Zayed Al Hammadi", "Reem Al Junaibi"};
    private static final String[] AR = {"أحمد الفارسي", "فاطمة الهاشمي", "خالد النعيمي", "مريم السويدي", "يوسف المزروعي", "نورة الكتبي",
            "سعيد الظاهري", "عائشة الشامسي", "حمد البلوشي", "لطيفة المهيري", "راشد العامري", "سلمى الزعابي", "عمر الكعبي",
            "حصة المري", "ماجد الفلاسي", "شمة القبيسي", "سلطان الطاير", "ميثاء الرميثي", "زايد الحمادي", "ريم الجنيبي"};
    private static final String[] FACULTY = {"Engineering", "Business", "Sciences", "Arts", "IT"};

    private DemoData() {}

    /** Demo student number: registration year 2026 followed by five digits (202610001 ... 202610020). */
    static String studentId(int index) { return String.format("2026%05d", 10_001 + index); }

    static void load(AppContext ctx) {
        PasswordHash pw = ctx.hasher.hash(PASSWORD.toCharArray());
        Map<String, User> byId = new LinkedHashMap<>();
        String[][] staff = {{"T1001", "Dr. Omar Al Mansoori", "د. عمر المنصوري"}, {"T1002", "Dr. Huda Al Ali", "د. هدى العلي"}, {"T1003", "Mr. Tariq Al Shehhi", "أ. طارق الشحي"}};
        ctx.persistence.tx.runInTransaction(() -> {
            for (String[] s : staff) byId.put(s[0], save(ctx, AccountType.STAFF, s[0], s[1], s[2], "Student Affairs", pw));
            for (int i = 0; i < 20; i++) {
                String id = studentId(i);
                byId.put(id, save(ctx, AccountType.STUDENT, id, EN[i], AR[i], FACULTY[i % FACULTY.length], pw));
            }
        });
        UserSession t1 = ctx.sessions.create(byId.get("T1001"));
        UserSession t2 = ctx.sessions.create(byId.get("T1002"));
        UserSession[] st = new UserSession[20];
        for (int i = 0; i < 20; i++) st[i] = ctx.sessions.create(byId.get(studentId(i)));

        // venues
        long[] v = new long[5];
        String[][] venues = {{"Main Auditorium", "القاعة الرئيسية", "400"}, {"Lecture Hall A", "قاعة المحاضرات أ", "120"}, {"Computer Lab 2", "مختبر الحاسوب 2", "40"},
                {"Sports Hall", "الصالة الرياضية", "200"}, {"Seminar Room 3", "غرفة الندوات 3", "30"}};
        ctx.persistence.tx.runInTransaction(() -> {
            for (int i = 0; i < 5; i++) {
                Venue x = new Venue();
                x.setName(new LocalizedText(venues[i][0], venues[i][1])); x.setBuilding("Campus"); x.setRoom(String.valueOf(i + 1));
                x.setCapacity(Integer.parseInt(venues[i][2])); x.setFacilities("Projector, Wi-Fi");
                v[i] = ctx.persistence.venues.save(x).getId();
            }
        });
        Map<String, Long> cat = new HashMap<>(), tag = new HashMap<>();
        ctx.persistence.tx.runInTransaction(() -> {
            for (CatalogRepository.Item i : ctx.persistence.catalog.findCategories()) cat.put(i.name().en(), i.id());
            for (CatalogRepository.Item i : ctx.persistence.catalog.findTags()) tag.put(i.name().en(), i.id());
        });

        // clubs with officers 202610001..202610003 (students 1-3)
        Club robotics = club(ctx, t1, "Robotics Club", "نادي الروبوتات", "Build and program robots.", "نبني ونبرمج الروبوتات.", cat.get("Technology"), st[0]);
        Club cultural = club(ctx, t1, "Cultural Society", "الجمعية الثقافية", "Celebrating culture and the arts.", "نحتفي بالثقافة والفنون.", cat.get("Cultural"), st[1]);
        Club sports = club(ctx, t1, "Sports Club", "النادي الرياضي", "Football, basketball and more.", "كرة القدم وكرة السلة والمزيد.", cat.get("Sports"), st[2]);
        // members
        for (int i = 3; i < 12; i++) {
            Club c = i % 3 == 0 ? robotics : i % 3 == 1 ? cultural : sports;
            UserSession officer = i % 3 == 0 ? st[0] : i % 3 == 1 ? st[1] : st[2];
            ctx.clubs.requestJoin(st[i], c.getId());
            for (MemberRow r : ctx.clubs.pendingRequests(officer, c.getId())) ctx.clubs.decide(officer, r.membership().getId(), true, null);
        }
        ctx.clubs.requestJoin(st[15], robotics.getId());   // left pending for the demo
        // interests
        ctx.users.updateProfile(st[3], studentId(3) + "@university.example", "IT", Language.EN, Set.of(tag.get("Programming"), tag.get("AI")));
        ctx.users.updateProfile(st[4], studentId(4) + "@university.example", "Arts", Language.AR, Set.of(tag.get("Music"), tag.get("Art")));
        ctx.users.updateProfile(st[5], studentId(5) + "@university.example", "Sciences", Language.EN, Set.of(tag.get("Football"), tag.get("Basketball")));

        // events (all created by Staff and published)
        long welcome = pub(ctx, t1, gen(ctx, EventType.GENERAL, "Welcome Week Fair", "معرض أسبوع الترحيب", "Meet clubs and services on campus.", "تعرّف على الأندية والخدمات في الجامعة.", cat.get("Social"), Set.of(tag.get("Networking")), 3, 10, 2, v[0], 300, null));
        long java = pub(ctx, t1, workshop(ctx, v[2], robotics.getId(), cat.get("Technology"), tag.get("Programming")));
        long robo = pub(ctx, t1, gen(ctx, EventType.COMPETITION, "Robotics Challenge", "تحدي الروبوتات", "Individual line-following challenge.", "تحدي تتبع الخط الفردي.", cat.get("Technology"), Set.of(tag.get("Robotics")), 10, 9, 4, v[1], 40, robotics.getId()));
        long football = pub(ctx, t1, gen(ctx, EventType.GENERAL, "Football Friendly", "مباراة كرة قدم ودية", "Small-sided friendly match.", "مباراة ودية.", cat.get("Sports"), Set.of(tag.get("Football")), 5, 17, 2, v[3], 3, sports.getId()));
        long career = pub(ctx, t1, gen(ctx, EventType.GENERAL, "Career Talk: AI in Industry", "حوار مهني: الذكاء الاصطناعي", "Industry leaders talk about AI careers.", "قادة الصناعة يتحدثون عن وظائف الذكاء الاصطناعي.", cat.get("Career"), Set.of(tag.get("AI")), 7, 11, 2, v[1], 100, null));
        long hoops = pub(ctx, t2, gen(ctx, EventType.GENERAL, "Basketball Tryouts", "اختبارات كرة السلة", "Open tryouts for the university team.", "اختبارات مفتوحة لفريق الجامعة.", cat.get("Sports"), Set.of(tag.get("Basketball")), 6, 16, 2, v[3], 60, sports.getId()));
        EventData series = gen(ctx, EventType.GENERAL, "Study Skills Seminar", "ندوة مهارات الدراسة", "Weekly seminar on study techniques.", "ندوة أسبوعية حول تقنيات الدراسة.", cat.get("Academic"), Set.of(tag.get("Research")), 8, 13, 1, v[4], 25, null);
        ctx.events.createSeries(t1, series, new RecurrenceRule(RecurrenceRule.Frequency.WEEKLY, 1, Set.of(), null, 4), Set.of(), true);
        long art = pub(ctx, t1, gen(ctx, EventType.GENERAL, "Art Exhibition", "معرض فني", "Student art on display.", "أعمال فنية للطلاب.", cat.get("Cultural"), Set.of(tag.get("Art")), 9, 10, 3, v[0], 200, cultural.getId()));
        ctx.events.cancel(t1, art, "Venue renovation");
        ctx.events.create(t1, gen(ctx, EventType.GENERAL, "Hackathon (draft)", "هاكاثون (مسودة)", "24-hour hackathon, details to follow.", "هاكاثون لمدة ٢٤ ساعة.", cat.get("Technology"), Set.of(tag.get("Programming")), 20, 9, 8, v[2], 40, null));
        // a proposal from the Cultural Society officer, waiting for approval
        Event proposal = ctx.events.create(st[1], gen(ctx, EventType.GENERAL, "Cultural Night", "الليلة الثقافية", "An evening of music, poetry and food.", "أمسية من الموسيقى والشعر والطعام.", cat.get("Cultural"), Set.of(tag.get("Music")), 14, 18, 3, v[0], 250, cultural.getId()));
        ctx.events.submit(st[1], proposal.getId());

        ctx.scheduler.tick();   // Published -> Open (registration opens immediately by default)

        // registrations: football has 3 seats, so 2 students end up on the waitlist
        for (int i = 3; i < 8; i++) ctx.registrations.register(st[i], football);
        for (int i = 3; i < 15; i++) ctx.registrations.register(st[i], welcome);
        for (int i = 3; i < 9; i++) ctx.registrations.register(st[i], career);
        for (int i = 3; i < 6; i++) ctx.registrations.register(st[i], java);
        for (int i = 3; i < 7; i++) ctx.registrations.register(st[i], robo);
        ctx.registrations.register(st[9], hoops);
        ctx.events.announce(t1, welcome, new LocalizedText("Welcome Week", "أسبوع الترحيب"), new LocalizedText("Bring your student card!", "أحضر بطاقتك الجامعية!"));
    }

    private static User save(AppContext ctx, AccountType t, String id, String en, String ar, String faculty, PasswordHash pw) {
        User u = User.create(t);
        u.setUniversityId(id); u.setFullName(new LocalizedText(en, ar)); u.setEmail(id.toLowerCase(Locale.ROOT) + "@university.example");
        u.setFaculty(faculty); u.setPasswordHash(pw); u.setMustChangePassword(false);
        return ctx.persistence.users.save(u);
    }

    private static Club club(AppContext ctx, UserSession staff, String en, String ar, String den, String dar, long category, UserSession officer) {
        Club c = new Club();
        c.setName(new LocalizedText(en, ar)); c.setDescription(new LocalizedText(den, dar)); c.setCategoryId(category);
        return ctx.clubs.create(staff, c, officer.getUserId());
    }

    private static long pub(AppContext ctx, UserSession staff, EventData d) {
        Event e = ctx.events.create(staff, d);
        ctx.events.publish(staff, e.getId());
        return e.getId();
    }

    private static Instant at(int dayOffset, int hour) {
        return LocalDate.now(Gst.ZONE).plusDays(dayOffset).atTime(hour, 0).atZone(Gst.ZONE).toInstant();
    }

    private static EventData gen(AppContext ctx, EventType type, String en, String ar, String den, String dar, Long cat, Set<Long> tags,
                                 int day, int hour, int hours, long venue, int cap, Long club) {
        EventData d = new EventData();
        d.type = type; d.title = new LocalizedText(en, ar); d.description = new LocalizedText(den, dar);
        d.categoryId = cat; d.tagIds = new HashSet<>(tags); d.startAt = at(day, hour); d.endAt = d.startAt.plus(Duration.ofHours(hours));
        d.venueId = venue; d.capacity = cap; d.hostClubId = club;
        return d;
    }

    private static EventData workshop(AppContext ctx, long venue, long club, long cat, long tag) {
        EventData d = new EventData();
        d.type = EventType.WORKSHOP; d.title = new LocalizedText("Intro to Java Workshop", "ورشة مقدمة في جافا");
        d.description = new LocalizedText("Three hands-on sessions on Java basics.", "ثلاث جلسات عملية حول أساسيات جافا.");
        d.categoryId = cat; d.tagIds = new HashSet<>(Set.of(tag)); d.venueId = venue; d.capacity = 30; d.hostClubId = club;
        String[][] topics = {{"Variables and types", "المتغيرات والأنواع"}, {"Classes and objects", "الأصناف والكائنات"}, {"Collections", "المجموعات"}};
        d.sessions = new ArrayList<>();
        for (int i = 0; i < 3; i++) d.sessions.add(new SessionData(null, new LocalizedText(topics[i][0], topics[i][1]), at(4 + i, 14), at(4 + i, 16), null));
        return d;
    }
}
