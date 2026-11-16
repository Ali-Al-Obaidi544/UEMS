package uems;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;

import static uems.DataTable.col;

// MODULE 1 - USERS, SECURITY AND INFRASTRUCTURE (screens)
// Swing screens for: login, profile, users, venues, audit log and system settings.

/** UC-01 login with the language toggle in the top bar, plus the public event list for guests (UC-03). */
final class LoginScreen extends Screen {
    private final JTextField id = new JTextField(18);
    private final JPasswordField pw = new JPasswordField(18);
    private final JLabel error = new JLabel(" ");

    public LoginScreen(UiContext ctx) {
        super(ctx);
        JPanel box = new JPanel(new GridBagLayout());
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(24, 32, 24, 32)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(4, 4, 12, 4);
        box.add(Ui.title(t("app.loginTitle")), c);
        c.gridwidth = 1; c.gridy++; c.insets = new Insets(4, 4, 4, 10); c.anchor = GridBagConstraints.LINE_START;
        box.add(new JLabel(t("lbl.universityId")), c);
        c.gridx = 1; c.insets = new Insets(4, 0, 4, 4); box.add(id, c);
        c.gridx = 0; c.gridy++; c.insets = new Insets(4, 4, 4, 10); box.add(new JLabel(t("lbl.password")), c);
        c.gridx = 1; c.insets = new Insets(4, 0, 4, 4); pw.setPreferredSize(id.getPreferredSize()); box.add(pw, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 2; error.setForeground(new Color(0xB00020)); box.add(error, c);
        JButton login = Ui.button(ctx, "btn.login", e -> doLogin());
        JButton guest = Ui.button(ctx, "btn.guest", e -> ctx.go(() -> new BrowseScreen(ctx)));
        c.gridy++; c.anchor = GridBagConstraints.CENTER; box.add(Ui.row(login, guest), c);
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.add(box);
        add(wrap, BorderLayout.CENTER);
        pw.addActionListener(e -> doLogin());
        id.addActionListener(e -> pw.requestFocusInWindow());
    }

    @Override public void onShow() { id.requestFocusInWindow(); }

    private void doLogin() {
        error.setText(" ");
        String uid = id.getText();
        char[] p = pw.getPassword();
        pw.setText("");
        new SwingWorker<Object, Void>() {
            @Override protected Object doInBackground() { return ctx.app.auth.login(uid, p); }
            @Override protected void done() {
                try {
                    UserSession s = (UserSession) get();
                    ctx.frame().setSessionAndContinue(s);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable ex = e.getCause();
                    if (ex instanceof UemsException u) error.setText(t(u.getMessageKey(), u.getArgs()));
                    else { error.setText(t("error.unexpected")); ex.printStackTrace(); }
                } catch (InterruptedException ignored) { }
            }
        }.execute();
    }
}

/** S10 profile: own data, language, interests, password change and competition placements (UC-18, FR-USR-05..07, FR-CMP-07). */
final class ProfileScreen extends Screen {
    private final boolean forced;
    private final JTextField email = new JTextField(24), faculty = new JTextField(24);
    private final JComboBox<Ui.Item<Language>> language = new JComboBox<>();
    private final JPanel interests = new JPanel(new GridLayout(0, 3));
    private final Map<Long, JCheckBox> boxes = new LinkedHashMap<>();
    private final JPasswordField oldPw = new JPasswordField(16), newPw = new JPasswordField(16), newPw2 = new JPasswordField(16);
    private final JLabel info = new JLabel(" ");

    public ProfileScreen(UiContext ctx, boolean forced) {
        super(ctx);
        this.forced = forced;
        language.addItem(new Ui.Item<>(Language.EN, "English")); language.addItem(new Ui.Item<>(Language.AR, "العربية"));
        JPanel col = new JPanel(); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        if (forced) { JLabel w = Ui.bold(t("msg.mustChangePassword")); w.setForeground(new Color(0xB00020)); col.add(w); }
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.universityId", new JLabel(ctx.session().getUniversityId()));
        f.add("lbl.name", new JLabel(ctx.msg.name(ctx.session().getDisplayName())));
        f.add("lbl.accountType", new JLabel(t("accountType." + ctx.session().getAccountType().name())));
        f.add("lbl.email", email).add("lbl.faculty", faculty).add("lbl.language", language);
        if (ctx.isStudent() && !forced) f.add("lbl.interests", interests);
        col.add(f);
        col.add(Ui.row(Ui.button(ctx, "btn.save", e -> save())));
        Ui.Form pw = new Ui.Form(ctx);
        pw.add("lbl.oldPassword", oldPw).add("lbl.newPassword", newPw).add("lbl.confirmPassword", newPw2);
        JPanel pwBox = new JPanel(new BorderLayout());
        pwBox.setBorder(BorderFactory.createTitledBorder(t("title.changePassword")));
        pwBox.add(pw, BorderLayout.CENTER);
        pwBox.add(Ui.row(Ui.button(ctx, "btn.changePassword", e -> changePassword()), info), BorderLayout.SOUTH);
        col.add(pwBox);
        if (ctx.isStudent() && !forced) {
            DataTable<CompetitionResult> t = new DataTable<>(ctx, List.of(col("col.title", r -> ctx.msg.name(r.getName())), col("col.placement", r -> String.valueOf(r.getPlacement()))));
            t.setPreferredSize(new Dimension(400, 120));
            JPanel pb = new JPanel(new BorderLayout()); pb.setBorder(BorderFactory.createTitledBorder(t("title.placements"))); pb.add(t);
            col.add(pb);
            ctx.run(this, () -> ctx.app.results.myPlacements(ctx.session()), t::setRows);
        }
        add(Ui.vscroll(col), BorderLayout.CENTER);
        add(Ui.title(t("title.profile")), BorderLayout.NORTH);
    }

    @Override public void onShow() {
        boolean student = ctx.isStudent() && !forced;
        ctx.run(this, () -> new Object[]{ctx.app.users.getProfile(ctx.session()), student ? ctx.app.users.getInterests(ctx.session()) : Set.<Long>of(),
                student ? ctx.app.catalog.tags(ctx.session()) : List.<CatalogRepository.Item>of()}, r -> {
            User u = (User) r[0];
            email.setText(u.getEmail()); faculty.setText(u.getFaculty());
            language.setSelectedIndex(u.getLanguage() == Language.AR ? 1 : 0);
            @SuppressWarnings("unchecked") Set<Long> mine = (Set<Long>) r[1];
            @SuppressWarnings("unchecked") List<CatalogRepository.Item> tags = (List<CatalogRepository.Item>) r[2];
            interests.removeAll(); boxes.clear();
            for (CatalogRepository.Item i : tags) { JCheckBox b = new JCheckBox(ctx.msg.name(i.name()), mine.contains(i.id())); boxes.put(i.id(), b); interests.add(b); }
            ctx.orient(interests); interests.revalidate();
        });
    }

    private void save() {
        Set<Long> sel = new HashSet<>();
        boxes.forEach((id, b) -> { if (b.isSelected()) sel.add(id); });
        Language lang = ((Ui.Item<Language>) language.getSelectedItem()).value();
        ctx.run(this, () -> { ctx.app.users.updateProfile(ctx.session(), email.getText(), faculty.getText(), lang, sel); return null; }, x -> {
            ctx.info(this, "msg.profileSaved");
            if (lang != ctx.msg.language()) ctx.setLanguage(lang);
        });
    }

    private void changePassword() {
        char[] o = oldPw.getPassword(), n = newPw.getPassword(), n2 = newPw2.getPassword();
        oldPw.setText(""); newPw.setText(""); newPw2.setText("");
        if (!Arrays.equals(n, n2)) { ctx.error(this, new ValidationException("error.password.mismatch")); return; }
        ctx.run(this, () -> { ctx.app.auth.changePassword(ctx.session(), o, n); return null; }, x -> {
            ctx.info(this, "msg.passwordChanged");
            if (forced) ctx.frame().passwordChanged();
        });
    }
}

/** S16 user administration: create, edit, disable, reset password, anonymize, CSV import (UC-02, FR-USR-01..04, FR-USR-10, FR-USR-11). */
final class UsersScreen extends Screen {
    private final JTextField search = new JTextField(16);
    private final JComboBox<Ui.Item<AccountType>> type = new JComboBox<>();
    private final JComboBox<Ui.Item<UserStatus>> status = new JComboBox<>();
    private final DataTable<User> table;

    public UsersScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(col("col.universityId", u -> u.getUniversityId()), col("col.name", u -> ctx.msg.name(u.getFullName())),
                col("col.accountType", u -> t("accountType." + u.accountType().name())), col("col.status", u -> t("userStatus." + u.getStatus().name())),
                col("col.faculty", User::getFaculty), col("col.email", User::getEmail)));
        type.addItem(new Ui.Item<>(null, t("opt.allTypes")));
        for (AccountType a : AccountType.values()) type.addItem(new Ui.Item<>(a, t("accountType." + a.name())));
        status.addItem(new Ui.Item<>(null, t("opt.allStates")));
        for (UserStatus s : UserStatus.values()) status.addItem(new Ui.Item<>(s, t("userStatus." + s.name())));
        add(Ui.title(t("title.users")), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(Ui.row(new JLabel(t("lbl.search")), search, type, status, Ui.button(ctx, "btn.search", e -> load())), BorderLayout.NORTH);
        center.add(table, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.newUser", e -> edit(null)), Ui.button(ctx, "btn.edit", e -> { if (table.selected() != null) edit(table.selected()); }),
                Ui.button(ctx, "btn.disableEnable", e -> toggle()), Ui.button(ctx, "btn.resetPassword", e -> reset()),
                Ui.button(ctx, "btn.anonymize", e -> anonymize()), Ui.button(ctx, "btn.importCsv", e -> importCsv())), BorderLayout.SOUTH);
        search.addActionListener(e -> load());
    }

    @Override public void onShow() { load(); }

    private void load() {
        AccountType a = ((Ui.Item<AccountType>) type.getSelectedItem()).value();
        UserStatus s = ((Ui.Item<UserStatus>) status.getSelectedItem()).value();
        ctx.run(this, () -> ctx.app.users.search(ctx.session(), search.getText(), a, s), table::setRows);
    }

    private void edit(User u) {
        JTextField id = new JTextField(u == null ? "" : u.getUniversityId(), 16), en = new JTextField(u == null ? "" : u.getFullName().en(), 24), ar = new JTextField(u == null ? "" : u.getFullName().ar(), 24);
        JTextField email = new JTextField(u == null ? "" : u.getEmail(), 24), fac = new JTextField(u == null ? "" : u.getFaculty(), 24);
        JComboBox<Ui.Item<AccountType>> at = new JComboBox<>();
        for (AccountType a : AccountType.values()) at.addItem(new Ui.Item<>(a, t("accountType." + a.name())));
        if (u != null) at.setSelectedIndex(u.accountType().ordinal());
        id.setToolTipText(t("lbl.idHint"));
        Ui.Form f = new Ui.Form(ctx);
        if (u == null) f.add((String) null, new JLabel("<html><small>" + t("lbl.idHint") + "</small></html>"));
        f.add("lbl.universityId", id).add("lbl.nameEn", en).add("lbl.nameAr", ar).add("lbl.email", email).add("lbl.accountType", at).add("lbl.faculty", fac);
        Ui.ask(ctx, this, u == null ? "title.newUser" : "title.editUser", f, () -> {
            UserData d = new UserData();
            d.universityId = id.getText(); d.fullName = new LocalizedText(en.getText(), ar.getText()); d.email = email.getText();
            d.accountType = ((Ui.Item<AccountType>) at.getSelectedItem()).value(); d.faculty = fac.getText(); d.language = u == null ? Language.EN : u.getLanguage();
            if (u == null) { String temp = ctx.app.users.create(ctx.session(), d); showNewUser(d.universityId, temp); } else ctx.app.users.update(ctx.session(), u.getId(), d);
            load();
            return true;
        });
    }

    /** Shows the ID (it may have been generated) and the temporary password once. */
    private void showNewUser(String universityId, String temp) {
        JTextField f = new JTextField(temp); f.setEditable(false);
        JPanel p = new JPanel(new BorderLayout(4, 4)); p.add(new JLabel(t("msg.newUserCredentials", universityId)), BorderLayout.NORTH); p.add(f, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, t("title.info"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void showTemp(String temp) {
        JTextField f = new JTextField(temp); f.setEditable(false);
        JPanel p = new JPanel(new BorderLayout(4, 4)); p.add(new JLabel(t("msg.tempPassword")), BorderLayout.NORTH); p.add(f, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, t("title.info"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void toggle() {
        User u = table.selected();
        if (u == null) return;
        boolean enable = u.getStatus() != UserStatus.ACTIVE;
        ctx.run(this, () -> { ctx.app.users.setActive(ctx.session(), u.getId(), enable); return null; }, x -> load());
    }

    private void reset() {
        User u = table.selected();
        if (u != null && ctx.confirm(this, "msg.confirmReset")) ctx.run(this, () -> ctx.app.users.resetPassword(ctx.session(), u.getId()), this::showTemp);
    }

    private void anonymize() {
        User u = table.selected();
        if (u != null && ctx.confirm(this, "msg.confirmAnonymize")) ctx.run(this, () -> { ctx.app.users.anonymize(ctx.session(), u.getId()); return null; }, x -> load());
    }

    private void importCsv() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        ctx.run(this, () -> ctx.app.users.importCsv(ctx.session(), Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8)), n -> { ctx.info(this, "msg.imported", n); load(); });
    }
}

/** S17 venue administration with timetable (UC-09, FR-VEN-01, FR-VEN-02, FR-VEN-06). */
final class VenuesScreen extends Screen {
    private final DataTable<Venue> table;

    public VenuesScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(col("col.name", v -> ctx.msg.name(v.getName())), col("col.building", Venue::getBuilding), col("col.room", Venue::getRoom),
                col("col.capacity", v -> String.valueOf(v.getCapacity())), col("col.status", v -> t(v.isActive() ? "status.active" : "status.inactive"))));
        add(Ui.title(t("title.venues")), BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.newVenue", e -> edit(null)), Ui.button(ctx, "btn.edit", e -> { if (table.selected() != null) edit(table.selected()); }),
                Ui.button(ctx, "btn.deactivate", e -> { Venue v = table.selected(); if (v != null && ctx.confirm(this, "msg.confirmDeactivate")) ctx.run(this, () -> { ctx.app.venues.deactivate(ctx.session(), v.getId()); return null; }, x -> load()); }),
                Ui.button(ctx, "btn.timetable", e -> { if (table.selected() != null) timetable(table.selected()); })), BorderLayout.SOUTH);
    }

    @Override public void onShow() { load(); }
    private void load() { ctx.run(this, () -> ctx.app.venues.list(ctx.session(), false), table::setRows); }

    private void edit(Venue existing) {
        JTextField en = new JTextField(existing == null ? "" : existing.getName().en(), 22), ar = new JTextField(existing == null ? "" : existing.getName().ar(), 22);
        JTextField building = new JTextField(existing == null ? "" : existing.getBuilding(), 14), room = new JTextField(existing == null ? "" : existing.getRoom(), 8);
        JTextField cap = new JTextField(existing == null ? "" : String.valueOf(existing.getCapacity()), 6), fac = new JTextField(existing == null ? "" : existing.getFacilities(), 22);
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.nameEn", en).add("lbl.nameAr", ar).add("lbl.building", building).add("lbl.room", room).add("lbl.capacity", cap).add("lbl.facilities", fac);
        Ui.ask(ctx, this, existing == null ? "title.newVenue" : "title.editVenue", f, () -> {
            Venue v = existing == null ? new Venue() : existing;
            v.setName(new LocalizedText(en.getText(), ar.getText())); v.setBuilding(building.getText()); v.setRoom(room.getText());
            v.setCapacity(Fmt.parseInt(cap.getText(), "error.venue.capacity")); v.setFacilities(fac.getText());
            ctx.app.venues.save(ctx.session(), v);
            load();
            return true;
        });
    }

    private void timetable(Venue v) {
        JTextField from = new JTextField(Fmt.format(Instant.now()).substring(0, 10), 10), to = new JTextField(Fmt.format(Instant.now().plus(java.time.Duration.ofDays(30))).substring(0, 10), 10);
        DataTable<Booking> t = new DataTable<>(ctx, List.of(col("col.title", b -> ctx.msg.name(b.eventTitle())), col("col.starts", b -> ctx.msg.dateTime(b.slot().start())), col("col.ends", b -> ctx.msg.time(b.slot().end()))));
        Runnable load = () -> {
            try {
                Instant f = Fmt.parseDay(from.getText(), false), e = Fmt.parseDay(to.getText(), true);
                ctx.run(this, () -> ctx.app.venues.timetable(ctx.session(), v.getId(), f, e), t::setRows);
            } catch (UemsException ex) { ctx.error(this, ex); }
        };
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(Ui.row(new JLabel(ctx.t("lbl.from")), from, new JLabel(ctx.t("lbl.to")), to, Ui.button(ctx, "btn.search", e -> load.run())), BorderLayout.NORTH);
        p.add(t, BorderLayout.CENTER);
        load.run();
        Ui.show(ctx, this, "title.timetable", p, 640, 420);
    }
}

/** S18 audit log and system settings (UC-02, FR-USR-08, FR-USR-09). */
final class AuditScreen extends Screen {
    private final JTextField from = new JTextField(9), to = new JTextField(9), actor = new JTextField(10), action = new JTextField(12);
    private final JTextField timeout = new JTextField(4), retention = new JTextField(4);
    private final DataTable<AuditEntry> table;

    public AuditScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(col("col.when", a -> ctx.msg.dateTime(a.at())), col("col.actor", a -> a.actorName() == null ? "" : a.actorName()),
                col("col.action", AuditEntry::action), col("col.target", a -> a.target() == null ? "" : a.target()), col("col.details", a -> a.details() == null ? "" : a.details())));
        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(Ui.title(t("title.audit")));
        top.add(Ui.row(new JLabel(t("lbl.from")), from, new JLabel(t("lbl.to")), to, new JLabel(t("lbl.actor")), actor, new JLabel(t("lbl.action")), action, Ui.button(ctx, "btn.search", e -> load())));
        add(top, BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        JPanel s = Ui.row(new JLabel(t("lbl.sessionTimeout")), timeout, new JLabel(t("lbl.retentionYears")), retention, Ui.button(ctx, "btn.save", e -> saveSettings()));
        s.setBorder(BorderFactory.createTitledBorder(t("title.settings")));
        add(s, BorderLayout.SOUTH);
    }

    @Override public void onShow() {
        load();
        ctx.run(this, () -> new int[]{ctx.app.settings.getInt(ctx.session(), SettingsService.TIMEOUT, 20), ctx.app.settings.getInt(ctx.session(), SettingsService.RETENTION, 3)}, r -> { timeout.setText(String.valueOf(r[0])); retention.setText(String.valueOf(r[1])); });
    }

    private void load() {
        try {
            var f = Fmt.parseDay(from.getText(), false); var e = Fmt.parseDay(to.getText(), true);
            ctx.run(this, () -> ctx.app.audit.search(ctx.session(), f, e, actor.getText(), action.getText(), 500), table::setRows);
        } catch (UemsException ex) { ctx.error(this, ex); }
    }

    private void saveSettings() {
        try {
            int t = Fmt.parseInt(timeout.getText(), "error.setting.range"), r = Fmt.parseInt(retention.getText(), "error.setting.range");
            ctx.run(this, () -> { ctx.app.settings.setInt(ctx.session(), SettingsService.TIMEOUT, t); ctx.app.settings.setInt(ctx.session(), SettingsService.RETENTION, r); return null; }, x -> ctx.info(this, "msg.saved"));
        } catch (UemsException ex) { ctx.error(this, ex); }
    }
}
