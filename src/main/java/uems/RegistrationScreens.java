package uems;

import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;

import static uems.DataTable.col;

// MODULE 3 - REGISTRATION AND ATTENDANCE (screens)
// Swing screens for: my registrations and calendar, notifications, and the organizer dialogs (registrants, check-in, results, announcement, history).

/** S09 notification center (UC-17, FR-NTF-01..06). */
final class NotificationsScreen extends Screen {
    private final DataTable<Notification> table;

    public NotificationsScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(
                col("col.status", n -> n.read() ? "" : "●"), col("col.type", n -> n.type()),
                col("col.message", n -> ctx.t(n.templateKey(), n.params().toArray())), col("col.when", n -> ctx.msg.dateTime(n.createdAt()))));
        table.onActivate(this::open);
        table.table().getColumnModel().getColumn(2).setPreferredWidth(560);
        add(Ui.title(t("title.notifications")), BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.open", e -> { if (table.selected() != null) open(table.selected()); }),
                Ui.button(ctx, "btn.markRead", e -> { Notification n = table.selected(); if (n != null) ctx.run(this, () -> { ctx.app.notifications.markRead(ctx.session(), n.id()); return null; }, x -> load()); }),
                Ui.button(ctx, "btn.markAllRead", e -> ctx.run(this, () -> { ctx.app.notifications.markAllRead(ctx.session()); return null; }, x -> load())),
                Ui.button(ctx, "btn.delete", e -> { Notification n = table.selected(); if (n != null) ctx.run(this, () -> { ctx.app.notifications.delete(ctx.session(), n.id()); return null; }, x -> load()); })), BorderLayout.SOUTH);
    }

    @Override public void onShow() { load(); }

    private void load() {
        ctx.run(this, () -> ctx.app.notifications.list(ctx.session(), 200), l -> { table.setRows(l); ctx.frame().refreshBadge(); });
    }

    private void open(Notification n) {
        ctx.run(this, () -> { ctx.app.notifications.markRead(ctx.session(), n.id()); return null; }, x -> ctx.frame().refreshBadge());
        String link = n.link();
        if (link == null) return;
        if (link.startsWith("event:")) ctx.go(() -> new EventDetailScreen(ctx, Long.parseLong(link.substring(6))));
        else if (link.startsWith("club:")) {
            long id = Long.parseLong(link.substring(5));
            if (ctx.officerClubs().contains(id)) ctx.go(() -> new ClubManageScreen(ctx, id)); else if (ctx.isStudent()) ctx.go(() -> new ClubsScreen(ctx));
        }
    }
}

/** S06 my registrations as a list and a month calendar, with .ics export (UC-06, UC-16, FR-CAL-01..04). */
final class MyRegistrationsScreen extends Screen {
    private final DataTable<MyRegistration> table;
    private final JPanel monthPanel = new JPanel(new BorderLayout());
    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private YearMonth month = YearMonth.now(Gst.ZONE);
    private List<MyRegistration> all = List.of();

    public MyRegistrationsScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(
                col("col.title", r -> ctx.msg.name(r.event().getTitle())),
                col("col.starts", r -> ctx.msg.dateTime(r.event().getStartAt())),
                col("col.eventStatus", r -> t("status." + r.event().status().name())),
                col("col.status", r -> t("regStatus." + r.registration().status().name())),
                col("col.position", r -> r.waitlistPosition() > 0 ? String.valueOf(r.waitlistPosition()) : "")));
        table.onActivate(r -> ctx.go(() -> new EventDetailScreen(ctx, r.event().getId())));
        stack.add(table, "list"); stack.add(monthPanel, "month");
        add(Ui.title(t("title.myRegistrations")), BorderLayout.NORTH);
        add(stack, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.viewList", e -> cards.show(stack, "list")), Ui.button(ctx, "btn.viewMonth", e -> { cards.show(stack, "month"); renderMonth(); }),
                Ui.button(ctx, "btn.view", e -> { if (table.selected() != null) ctx.go(() -> new EventDetailScreen(ctx, table.selected().event().getId())); }),
                Ui.button(ctx, "btn.cancelRegistration", e -> cancel()),
                Ui.button(ctx, "btn.exportAll", e -> saveIcs(ctx, this, () -> ctx.app.calendar.exportMine(ctx.session()), "my-events.ics")),
                Ui.button(ctx, "btn.exportSelected", e -> {
                    var sel = table.selectedAll().stream().map(r -> r.event().getId()).toList();
                    if (!sel.isEmpty()) saveIcs(ctx, this, () -> ctx.app.calendar.exportEvents(ctx.session(), sel), "selected-events.ics");
                })), BorderLayout.SOUTH);
    }

    @Override public void onShow() { load(); }

    private void load() {
        ctx.run(this, () -> ctx.app.registrations.findMine(ctx.session()), r -> { all = r; table.setRows(r); renderMonth(); });
    }

    private void cancel() {
        MyRegistration r = table.selected();
        if (r == null) return;
        if (ctx.confirm(this, "msg.confirmCancelRegistration")) ctx.run(this, () -> { ctx.app.registrations.cancel(ctx.session(), r.registration().getId()); return null; }, x -> load());
    }

    /** Lets the user choose a file and writes the iCalendar text produced by the service (SUC-7). */
    static void saveIcs(UiContext ctx, Component owner, Callable<String> content, String name) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(ctx.t("title.saveIcs"));
        fc.setSelectedFile(new java.io.File(name));
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        ctx.run(owner, content, text -> {
            try { Files.writeString(f.toPath(), text, StandardCharsets.UTF_8); ctx.info(owner, "msg.icsSaved", f.getName()); }
            catch (IOException e) { ctx.error(owner, new ValidationException("error.file.write")); }
        });
    }

    private void renderMonth() {
        monthPanel.removeAll();
        Locale loc = ctx.msg.isRtl() ? Locale.forLanguageTag("ar-u-nu-latn") : Locale.ENGLISH;
        JPanel nav = Ui.row(Ui.button(ctx, "btn.prev", e -> { month = month.minusMonths(1); renderMonth(); }),
                Ui.bold(month.getMonth().getDisplayName(TextStyle.FULL, loc) + " " + month.getYear()),
                Ui.button(ctx, "btn.next", e -> { month = month.plusMonths(1); renderMonth(); }));
        JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
        for (DayOfWeek d : DayOfWeek.values()) grid.add(Ui.bold(d.getDisplayName(TextStyle.SHORT, loc)));
        LocalDate first = month.atDay(1);
        for (int i = 1; i < first.getDayOfWeek().getValue(); i++) grid.add(new JLabel());
        Map<LocalDate, List<String>> byDay = new HashMap<>();
        for (MyRegistration r : all) {
            if (r.registration().status() == RegistrationStatus.CANCELLED || r.event().status() == EventStatus.CANCELLED) continue;
            for (TimeSlot s : r.event().getTimeSlots()) {
                LocalDate d = s.start().atZone(Gst.ZONE).toLocalDate();
                byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(ctx.msg.time(s.start()) + " " + ctx.msg.name(r.event().getTitle()) + (r.registration().status() == RegistrationStatus.WAITLISTED ? " (" + t("regStatus.WAITLISTED") + ")" : ""));
            }
        }
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate d = month.atDay(day);
            JPanel cell = new JPanel(); cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBorder(BorderFactory.createLineBorder(d.equals(LocalDate.now(Gst.ZONE)) ? new Color(0x1565C0) : Color.LIGHT_GRAY));
            cell.add(Ui.bold(String.valueOf(day)));
            for (String s : byDay.getOrDefault(d, List.of())) { JLabel l = new JLabel("<html><small>" + s.replace("<", "&lt;") + "</small></html>"); cell.add(l); }
            grid.add(cell);
        }
        monthPanel.add(nav, BorderLayout.NORTH);
        monthPanel.add(new JScrollPane(grid), BorderLayout.CENTER);
        ctx.orient(monthPanel);
        monthPanel.revalidate(); monthPanel.repaint();
    }
}

/** Organizer dialogs for an event: registrants, check-in, results, announcement and history (UC-14, UC-13, FR-REG-11, FR-NTF-07). */
final class EventDialogs {
    private EventDialogs() {}

    /** Registrant list with search and cancel-on-behalf (FR-REG-11, FR-WTL-06). */
    static void registrants(UiContext ctx, Component owner, Event e, Runnable changed) {
        DataTable<RegistrantRow> table = new DataTable<>(ctx, List.of(
                col("col.universityId", r -> r.universityId()), col("col.name", r -> ctx.msg.name(r.name())),
                col("col.status", r -> ctx.t("regStatus." + r.registration().status().name())),
                col("col.registered", r -> ctx.msg.dateTime(r.registration().getRegisteredAt())),
                col("col.position", r -> r.waitlistPosition() > 0 ? String.valueOf(r.waitlistPosition()) : "")));
        JTextField search = new JTextField(16);
        Runnable load = () -> ctx.run(owner, () -> ctx.app.registrations.findRegistrants(ctx.session(), e.getId(), search.getText()), table::setRows);
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(Ui.row(new JLabel(ctx.t("lbl.search")), search, Ui.button(ctx, "btn.search", a -> load.run()),
                Ui.button(ctx, "btn.cancelRegistration", a -> {
                    RegistrantRow r = table.selected();
                    if (r == null) return;
                    String reason = ctx.prompt(owner, "lbl.reason");
                    if (reason != null) ctx.run(owner, () -> { ctx.app.registrations.cancelOnBehalf(ctx.session(), r.registration().getId(), reason); return null; }, x -> { load.run(); changed.run(); });
                })), BorderLayout.NORTH);
        p.add(table, BorderLayout.CENTER);
        search.addActionListener(a -> load.run());
        load.run();
        Ui.show(ctx, owner, "title.registrants", p, 760, 480);
    }

    /** Manual check-in with undo; workshops by session (UC-14, FR-CHK-01..06). */
    static void attendance(UiContext ctx, Component owner, Event e) {
        DataTable<RegistrantRow> table = new DataTable<>(ctx, List.of(
                col("col.universityId", r -> r.universityId()), col("col.name", r -> ctx.msg.name(r.name())),
                col("col.status", r -> ctx.t("regStatus." + r.registration().status().name()))));
        JLabel totals = new JLabel(" ");
        JTextField search = new JTextField(14);
        JComboBox<Ui.Item<Long>> session = new JComboBox<>();
        if (e instanceof Workshop w) for (WorkshopSession s : w.getSessions()) session.addItem(new Ui.Item<>(s.getId(), s.getSeq() + ". " + ctx.msg.name(s.getTopic()) + " – " + ctx.msg.dateTime(s.getSlot().start())));
        Runnable load = () -> ctx.run(owner, () -> ctx.app.checkIn.attendanceList(ctx.session(), e.getId(), search.getText()), (AttendanceView v) -> {
            table.setRows(v.rows());
            totals.setText(ctx.t("msg.attendanceTotals", v.present(), v.absent(), v.confirmed()));
        });
        Long[] sid = new Long[1];
        java.util.function.Supplier<Long> sel = () -> session.getItemCount() == 0 ? null : ((Ui.Item<Long>) session.getSelectedItem()).value();
        JPanel top = Ui.row(new JLabel(ctx.t("lbl.search")), search, Ui.button(ctx, "btn.search", a -> load.run()));
        if (session.getItemCount() > 0) { top.add(new JLabel(ctx.t("lbl.session"))); top.add(session); }
        top.add(Ui.button(ctx, "btn.markPresent", a -> { RegistrantRow r = table.selected(); if (r != null) ctx.run(owner, () -> { ctx.app.checkIn.markPresent(ctx.session(), r.registration().getId(), sel.get()); return null; }, x -> load.run()); }));
        top.add(Ui.button(ctx, "btn.markAbsent", a -> { RegistrantRow r = table.selected(); if (r != null) ctx.run(owner, () -> { ctx.app.checkIn.markAbsent(ctx.session(), r.registration().getId(), sel.get()); return null; }, x -> load.run()); }));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(top, BorderLayout.NORTH); p.add(table, BorderLayout.CENTER); p.add(totals, BorderLayout.SOUTH);
        search.addActionListener(a -> load.run());
        load.run();
        Ui.show(ctx, owner, "title.checkIn", p, 860, 500);
    }

    /** Record, publish or correct competition results (UC-13, FR-CMP-02..05). */
    static void results(UiContext ctx, Component owner, Event e) {
        Map<Long, int[]> placement = new LinkedHashMap<>();
        Map<Long, BigDecimal> scores = new HashMap<>();
        DataTable<RegistrantRow> table = new DataTable<>(ctx, List.of(
                col("col.universityId", r -> r.universityId()), col("col.name", r -> ctx.msg.name(r.name())),
                col("col.placement", r -> placement.containsKey(r.registration().getUserId()) ? String.valueOf(placement.get(r.registration().getUserId())[0]) : ""),
                col("col.score", r -> scores.containsKey(r.registration().getUserId()) ? scores.get(r.registration().getUserId()).toPlainString() : "")));
        boolean[] published = {false};
        JLabel info = new JLabel(" ");
        Runnable load = () -> ctx.run(owner, () -> new Object[]{ctx.app.results.placeableParticipants(ctx.session(), e.getId()), ctx.app.results.results(ctx.session(), e.getId()), ctx.app.results.isPublished(ctx.session(), e.getId())}, r -> {
            @SuppressWarnings("unchecked") List<RegistrantRow> parts = (List<RegistrantRow>) r[0];
            @SuppressWarnings("unchecked") List<CompetitionResult> res = (List<CompetitionResult>) r[1];
            published[0] = (Boolean) r[2];
            placement.clear(); scores.clear();
            for (var c : res) { placement.put(c.getUserId(), new int[]{c.getPlacement()}); if (c.getScore() != null) scores.put(c.getUserId(), c.getScore()); }
            table.setRows(parts);
            info.setText(ctx.t(published[0] ? "msg.resultsPublished" : "msg.resultsDraft"));
        });
        java.util.function.Supplier<List<ResultService.Entry>> entries = () -> {
            List<ResultService.Entry> l = new ArrayList<>();
            for (var en : placement.entrySet()) l.add(new ResultService.Entry(en.getKey(), en.getValue()[0], scores.get(en.getKey())));
            return l;
        };
        JPanel buttons = Ui.row();
        for (int i = 1; i <= 3; i++) {
            int place = i;
            buttons.add(Ui.button(ctx, "btn.place" + i, a -> { RegistrantRow r = table.selected(); if (r != null) { placement.put(r.registration().getUserId(), new int[]{place}); table.table().repaint(); } }));
        }
        buttons.add(Ui.button(ctx, "btn.clearPlacement", a -> { RegistrantRow r = table.selected(); if (r != null) { placement.remove(r.registration().getUserId()); scores.remove(r.registration().getUserId()); table.table().repaint(); } }));
        buttons.add(Ui.button(ctx, "btn.setScore", a -> {
            RegistrantRow r = table.selected();
            if (r == null) return;
            String s = JOptionPane.showInputDialog(owner, ctx.t("lbl.score"));
            if (s == null) return;
            try { if (s.isBlank()) scores.remove(r.registration().getUserId()); else scores.put(r.registration().getUserId(), new BigDecimal(s.trim())); table.table().repaint(); }
            catch (NumberFormatException ex) { ctx.error(owner, new ValidationException("error.results.score")); }
        }));
        buttons.add(Ui.button(ctx, "btn.saveDraft", a -> ctx.run(owner, () -> { ctx.app.results.saveDraft(ctx.session(), e.getId(), entries.get()); return null; }, x -> load.run())));
        buttons.add(Ui.button(ctx, "btn.publishResults", a -> ctx.run(owner, () -> { ctx.app.results.publish(ctx.session(), e.getId()); return null; }, x -> load.run())));
        if (ctx.isStaff()) buttons.add(Ui.button(ctx, "btn.correctResults", a -> ctx.run(owner, () -> { ctx.app.results.correct(ctx.session(), e.getId(), entries.get()); return null; }, x -> load.run())));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(buttons, BorderLayout.NORTH); p.add(table, BorderLayout.CENTER); p.add(info, BorderLayout.SOUTH);
        load.run();
        Ui.show(ctx, owner, "title.results", p, 900, 480);
    }

    /** Announcement to registrants (FR-NTF-07). */
    static void announce(UiContext ctx, Component owner, Event e) {
        JTextField sEn = new JTextField(30), sAr = new JTextField(30);
        JTextArea mEn = Ui.textArea(3, 30), mAr = Ui.textArea(3, 30);
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.subjectEn", sEn).add("lbl.subjectAr", sAr).add("lbl.messageEn", new JScrollPane(mEn)).add("lbl.messageAr", new JScrollPane(mAr));
        Ui.ask(ctx, owner, "title.announce", f, () -> {
            int n = ctx.app.events.announce(ctx.session(), e.getId(), new LocalizedText(sEn.getText(), sAr.getText()), new LocalizedText(mEn.getText(), mAr.getText()));
            ctx.info(owner, "msg.announcementSent", n);
            return true;
        });
    }

    /** Lifecycle history (FR-LCY-09). */
    static void history(UiContext ctx, Component owner, Event e) {
        DataTable<EventRepository.StateChange> t = new DataTable<>(ctx, List.of(
                col("col.when", h -> ctx.msg.dateTime(h.at())), col("col.from", h -> h.from() == null ? "" : ctx.t("status." + h.from().name())),
                col("col.to", h -> ctx.t("status." + h.to().name())), col("col.note", h -> h.note() == null ? "" : h.note())));
        ctx.run(owner, () -> ctx.app.events.history(ctx.session(), e.getId()), t::setRows);
        Ui.show(ctx, owner, "title.history", t, 640, 380);
    }
}
