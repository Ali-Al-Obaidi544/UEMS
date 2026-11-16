package uems;

import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;

import static uems.DataTable.col;

// MODULE 2 - EVENTS AND LIFECYCLE (screens)
// Swing screens for: event details, event editor and event management.

/** S05 event details with role- and state-dependent actions (UC-05, UC-06, UC-07, UC-08, UC-12..15). */
final class EventDetailScreen extends Screen {
    private final long eventId;
    private final JPanel content = new JPanel(new BorderLayout(8, 8));

    private record View(Event e, SeatInfo seats, EventRepositoryPoster poster, String venue, String category, List<String> tags, String club, int[] series, Clash clash) {}
    private record EventRepositoryPoster(byte[] data) {}

    public EventDetailScreen(UiContext ctx, long eventId) {
        super(ctx);
        this.eventId = eventId;
        add(content, BorderLayout.CENTER);
    }

    @Override public void onShow() { load(); }

    private void load() {
        ctx.run(this, () -> {
            Event e = ctx.isGuest() ? ctx.app.events.getPublic(eventId) : ctx.app.events.get(ctx.session(), eventId);
            SeatInfo seats = ctx.isGuest() ? null : ctx.app.registrations.seatInfo(ctx.session(), eventId);
            var poster = ctx.isGuest() ? Optional.<EventRepository.Poster>empty() : ctx.app.events.poster(ctx.session(), eventId);
            List<Venue> venues = ctx.isGuest() ? ctx.app.venues.publicList() : ctx.app.venues.list(ctx.session(), false);
            String venue = venues.stream().filter(v -> e.getVenueId() != null && v.getId() == e.getVenueId()).map(v -> ctx.msg.name(v.getName())).findFirst().orElse("");
            String category = "", club = "";
            List<String> tags = new ArrayList<>();
            if (!ctx.isGuest()) {
                for (CatalogRepository.Item i : ctx.app.catalog.categories(ctx.session())) if (e.getCategoryId() != null && i.id() == e.getCategoryId()) category = ctx.msg.name(i.name());
                for (CatalogRepository.Item i : ctx.app.catalog.tags(ctx.session())) if (e.getTagIds().contains(i.id())) tags.add(ctx.msg.name(i.name()));
                if (e.getHostClubId() != null) club = ctx.msg.name(ctx.app.clubs.names(ctx.session(), List.of(e.getHostClubId())).get(e.getHostClubId()));
            }
            int[] series = ctx.isGuest() ? null : ctx.app.events.seriesPosition(ctx.session(), e);
            Clash clash = ctx.isStudent() && e.status() == EventStatus.OPEN && seats.myStatus() == null ? ctx.app.registrations.previewClash(ctx.session(), eventId).orElse(null) : null;
            return new View(e, seats, new EventRepositoryPoster(poster.map(p -> p.data()).orElse(null)), venue, category, tags, club, series, clash);
        }, this::render);
    }

    private boolean isOrganizer(Event e) { return ctx.isStaff() || (e.getHostClubId() != null && ctx.officerClubs().contains(e.getHostClubId())); }

    private void render(View v) {
        Event e = v.e();
        content.removeAll();
        JPanel head = new JPanel(new GridLayout(0, 1));
        head.add(Ui.title(ctx.msg.name(e.getTitle())));
        String sub = ctx.t("type." + e.type().name()) + "  •  " + ctx.t("status." + e.status().name())
                + (v.series() != null ? "  •  " + ctx.t("msg.seriesPos", v.series()[0], v.series()[1]) : "");
        head.add(new JLabel(sub));
        content.add(head, BorderLayout.NORTH);

        Ui.Form info = new Ui.Form(ctx);
        if (e.getStartAt() != null) info.add("lbl.when", new JLabel(ctx.msg.dateTime(e.getStartAt()) + " – " + ctx.msg.time(e.getEndAt())));
        info.add("lbl.venue", new JLabel(v.venue()));
        if (!v.category().isEmpty()) info.add("lbl.category", new JLabel(v.category()));
        if (!v.tags().isEmpty()) info.add("lbl.tags", new JLabel(String.join(", ", v.tags())));
        if (!v.club().isEmpty()) info.add("lbl.hostClub", new JLabel(v.club()));
        if (v.seats() != null) info.add("lbl.seats", new JLabel(ctx.t("msg.seatsLine", v.seats().confirmed(), v.seats().capacity(), v.seats().seatsLeft(), v.seats().waitlisted())));
        else info.add("lbl.capacity", new JLabel(String.valueOf(e.getCapacity())));
        if (e.status().isPublic() && e.getStartAt() != null) {
            info.add("lbl.regDeadline", new JLabel(ctx.msg.dateTime(e.effectiveRegDeadline())));
            info.add("lbl.cancelDeadline", new JLabel(ctx.msg.dateTime(e.effectiveCancelDeadline())));
        }
        if (e.getCancelReason() != null && e.status() == EventStatus.CANCELLED) info.add("lbl.cancelReason", new JLabel(e.getCancelReason()));
        if (e.getRejectionReason() != null && e.status() == EventStatus.DRAFT) info.add("lbl.rejectionReason", new JLabel(e.getRejectionReason()));
        if (!e.getDescription().isBlank()) info.add("lbl.description", Ui.readOnlyText(ctx.msg.name(e.getDescription())));
        if (v.seats() != null && v.seats().myStatus() != null) {
            String s = ctx.t("regStatus." + v.seats().myStatus().name()) + (v.seats().myStatus() == RegistrationStatus.WAITLISTED ? " (#" + v.seats().myPosition() + ")" : "");
            JLabel l = Ui.bold(s); l.setForeground(new Color(0x1B5E20));
            info.add("lbl.yourStatus", l);
        }
        if (v.clash() != null) { JLabel w = new JLabel(ctx.t("error.clash", v.clash().eventTitle(), v.clash().slot())); w.setForeground(new Color(0xB00020)); info.add("lbl.warning", w); }

        JPanel mid = new JPanel(new BorderLayout(8, 8));
        mid.add(info, BorderLayout.CENTER);
        if (v.poster().data() != null) {
            ImageIcon icon = new ImageIcon(v.poster().data());
            if (icon.getIconWidth() > 0) mid.add(new JLabel(new ImageIcon(icon.getImage().getScaledInstance(220, -1, Image.SCALE_SMOOTH))), BorderLayout.LINE_END);
        }
        JTabbedPane tabs = new JTabbedPane();
        if (e instanceof Workshop w) tabs.add(ctx.t("tab.sessions"), sessions(w));
        if (!ctx.isGuest() && e.status().isPublic()) tabs.add(ctx.t("tab.discussion"), new DiscussionPanel(ctx, eventId));
        if (!ctx.isGuest() && (e.status() == EventStatus.COMPLETED || e.status() == EventStatus.ONGOING)) tabs.add(ctx.t("tab.feedback"), new FeedbackPanel(ctx, eventId));
        if (e instanceof Competition && !ctx.isGuest() && (e.status() == EventStatus.COMPLETED || e.status() == EventStatus.ONGOING)) tabs.add(ctx.t("tab.results"), resultsTab());
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(mid, BorderLayout.NORTH);
        if (tabs.getTabCount() > 0) center.add(tabs, BorderLayout.CENTER);
        content.add(Ui.vscroll(center), BorderLayout.CENTER);
        content.add(actions(v), BorderLayout.SOUTH);
        ctx.orient(content);
        content.revalidate(); content.repaint();
    }

    private JComponent sessions(Workshop w) {
        DataTable<WorkshopSession> t = new DataTable<>(ctx, List.of(
                col("col.no", s -> String.valueOf(s.getSeq())), col("col.topic", s -> ctx.msg.name(s.getTopic())),
                col("col.starts", s -> ctx.msg.dateTime(s.getSlot().start())), col("col.ends", s -> ctx.msg.time(s.getSlot().end()))));
        t.setRows(w.getSessions());
        t.setPreferredSize(new Dimension(400, 140));
        return t;
    }

    private JComponent resultsTab() {
        DataTable<CompetitionResult> t = new DataTable<>(ctx, List.of(
                col("col.placement", r -> String.valueOf(r.getPlacement())), col("col.name", r -> ctx.msg.name(r.getName())),
                col("col.score", r -> r.getScore() == null ? "" : r.getScore().toPlainString())));
        JPanel p = new JPanel(new BorderLayout());
        JLabel msg = new JLabel(" ");
        p.add(t, BorderLayout.CENTER); p.add(msg, BorderLayout.SOUTH);
        ctx.run(this, () -> ctx.app.results.isPublished(ctx.session(), eventId) || isOrganizer(ctx.app.events.get(ctx.session(), eventId)) ? ctx.app.results.results(ctx.session(), eventId) : null, r -> {
            if (r == null) msg.setText(ctx.t("msg.resultsNotPublished")); else t.setRows(r);
        });
        return p;
    }

    // ------------------------------------------------------------ actions
    private JPanel actions(View v) {
        Event e = v.e();
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
        p.add(Ui.button(ctx, "btn.back", a -> ctx.back()));
        if (ctx.isGuest()) { p.add(Ui.button(ctx, "btn.loginToRegister", a -> ctx.frame().showLogin())); return p; }
        SeatInfo s = v.seats();
        if (ctx.isStudent() && s != null) {
            if (s.myStatus() == null && e.status() == EventStatus.OPEN) {
                JButton reg = Ui.button(ctx, s.seatsLeft() > 0 ? "btn.register" : "btn.joinWaitlist", a -> ctx.run(this, () -> ctx.app.registrations.register(ctx.session(), eventId), r -> {
                    ctx.info(this, r.status() == RegistrationStatus.CONFIRMED ? "msg.registered" : "msg.waitlisted");
                    load();
                }));
                reg.setEnabled(v.clash() == null);
                p.add(reg);
            }
            if (s.myStatus() == RegistrationStatus.CONFIRMED || s.myStatus() == RegistrationStatus.WAITLISTED)
                p.add(Ui.button(ctx, "btn.cancelRegistration", a -> { if (ctx.confirm(this, "msg.confirmCancelRegistration")) ctx.run(this, () -> { ctx.app.registrations.cancel(ctx.session(), s.myRegistrationId()); return null; }, x -> load()); }));
        }
        if (e.status().isPublic() && e.status() != EventStatus.CANCELLED) p.add(Ui.button(ctx, "btn.addToCalendar", a -> exportIcs(List.of(eventId))));
        if (isOrganizer(e)) organizerButtons(p, e);
        return p;
    }

    private void organizerButtons(JPanel p, Event e) {
        EventStatus st = e.status();
        boolean staff = ctx.isStaff();
        boolean mine = e.getCreatedBy() == ctx.session().getUserId();
        boolean editable = switch (st) { case DRAFT -> e.isProposal() ? mine : staff; case PUBLISHED, OPEN, CLOSED -> staff; default -> false; };
        if (editable) p.add(Ui.button(ctx, "btn.edit", a -> ctx.go(() -> new EventEditorScreen(ctx, e))));
        if (st == EventStatus.DRAFT && staff && !e.isProposal()) p.add(Ui.button(ctx, "btn.publish", a -> act(() -> ctx.app.events.publish(ctx.session(), eventId))));
        if (st == EventStatus.DRAFT && e.isProposal() && mine) p.add(Ui.button(ctx, "btn.submit", a -> act(() -> ctx.app.events.submit(ctx.session(), eventId))));
        if (st == EventStatus.PENDING_APPROVAL && mine) p.add(Ui.button(ctx, "btn.withdraw", a -> act(() -> ctx.app.events.withdraw(ctx.session(), eventId))));
        if (st == EventStatus.PENDING_APPROVAL && staff) {
            p.add(Ui.button(ctx, "btn.approve", a -> act(() -> ctx.app.events.approve(ctx.session(), eventId))));
            p.add(Ui.button(ctx, "btn.reject", a -> { String r = ctx.prompt(this, "lbl.reason"); if (r != null) act(() -> ctx.app.events.reject(ctx.session(), eventId, r)); }));
        }
        if (staff && st == EventStatus.OPEN) p.add(Ui.button(ctx, "btn.closeRegistration", a -> act(() -> ctx.app.events.closeRegistration(ctx.session(), eventId))));
        if (staff && (st == EventStatus.PUBLISHED || st == EventStatus.OPEN || st == EventStatus.CLOSED))
            p.add(Ui.button(ctx, "btn.cancelEvent", a -> { String r = ctx.prompt(this, "lbl.reason"); if (r != null) act(() -> ctx.app.events.cancel(ctx.session(), eventId, r)); }));
        if (st == EventStatus.DRAFT && (e.isProposal() ? mine : staff)) p.add(Ui.button(ctx, "btn.delete", a -> { if (ctx.confirm(this, "msg.confirmDelete")) ctx.run(this, () -> { ctx.app.events.delete(ctx.session(), eventId); return null; }, x -> ctx.back()); }));
        if (staff) p.add(Ui.button(ctx, "btn.copy", a -> ctx.run(this, () -> ctx.app.events.copy(ctx.session(), eventId), c -> ctx.go(() -> new EventEditorScreen(ctx, c)))));
        if (st.isPublic()) {
            p.add(Ui.button(ctx, "btn.registrants", a -> EventDialogs.registrants(ctx, this, e, this::load)));
            p.add(Ui.button(ctx, "btn.announce", a -> EventDialogs.announce(ctx, this, e)));
        }
        if (st == EventStatus.OPEN || st == EventStatus.CLOSED || st == EventStatus.ONGOING || st == EventStatus.COMPLETED) p.add(Ui.button(ctx, "btn.checkIn", a -> EventDialogs.attendance(ctx, this, e)));
        if (e instanceof Competition && (st == EventStatus.ONGOING || st == EventStatus.COMPLETED)) p.add(Ui.button(ctx, "btn.results", a -> EventDialogs.results(ctx, this, e)));
        p.add(Ui.button(ctx, "btn.history", a -> EventDialogs.history(ctx, this, e)));
    }

    private void act(Runnable r) { ctx.run(this, () -> { r.run(); return null; }, x -> load()); }

    private void exportIcs(java.util.Collection<Long> ids) { MyRegistrationsScreen.saveIcs(ctx, this, () -> ctx.app.calendar.exportEvents(ctx.session(), ids), "event-" + eventId + ".ics"); }
}

/** S12 event editor: all event types, workshop sessions, recurrence, poster and venue availability (UC-07, UC-08, UC-12, FR-EVT-02, FR-REC-01). */
final class EventEditorScreen extends Screen {
    private final Event existing;
    private final Long fixedClub;
    private final JComboBox<Ui.Item<EventType>> type = new JComboBox<>();
    private final JTextField titleEn = new JTextField(30), titleAr = new JTextField(30);
    private final JTextArea descEn = Ui.textArea(3, 30), descAr = Ui.textArea(3, 30);
    private final JComboBox<Ui.Item<Long>> category = new JComboBox<>(), venue = new JComboBox<>(), club = new JComboBox<>();
    private final JPanel tagPanel = new JPanel(new GridLayout(0, 4));
    private final Map<Long, JCheckBox> tagBoxes = new LinkedHashMap<>();
    private final JTextField capacity = new JTextField(6), start = new JTextField(14), end = new JTextField(14);
    private final JTextField regOpens = new JTextField(14), regDeadline = new JTextField(14), cancelDeadline = new JTextField(14);
    private final JLabel posterName = new JLabel(" ");
    private final JCheckBox clearPoster = new JCheckBox();
    private byte[] poster; private String posterType;
    private final List<SessionData> sessions = new ArrayList<>();
    private DataTable<SessionData> sessionTable;
    private final JPanel timesRow = new JPanel(new FlowLayout(FlowLayout.LEADING));
    private final JPanel sessionBox = new JPanel(new BorderLayout());
    private final JCheckBox repeat = new JCheckBox();
    private final JComboBox<RecurrenceRule.Frequency> freq = new JComboBox<>(RecurrenceRule.Frequency.values());
    private final JTextField interval = new JTextField("1", 3), until = new JTextField(10), count = new JTextField(4);
    private final Map<DayOfWeek, JCheckBox> days = new EnumMap<>(DayOfWeek.class);
    private final JPanel recurrence = new JPanel(new FlowLayout(FlowLayout.LEADING));
    private final Ui.Form form;

    /** Staff: new event. */
    public EventEditorScreen(UiContext ctx) { this(ctx, null, null); }
    /** Officer: new proposal for the club. */
    public EventEditorScreen(UiContext ctx, Long clubId) { this(ctx, null, clubId); }
    /** Edit an existing event. */
    public EventEditorScreen(UiContext ctx, Event existing) { this(ctx, existing, null); }

    private EventEditorScreen(UiContext ctx, Event existing, Long clubId) {
        super(ctx);
        this.existing = existing; this.fixedClub = clubId;
        for (EventType et : EventType.values()) type.addItem(new Ui.Item<>(et, t("type." + et.name())));
        type.setEnabled(existing == null);
        clearPoster.setText(t("lbl.removePoster"));
        for (DayOfWeek d : DayOfWeek.values()) days.put(d, new JCheckBox(d.getDisplayName(java.time.format.TextStyle.SHORT, ctx.msg.isRtl() ? Locale.forLanguageTag("ar") : Locale.ENGLISH)));
        form = new Ui.Form(ctx);
        form.add("lbl.type", type).add("lbl.titleEn", titleEn).add("lbl.titleAr", titleAr)
            .add("lbl.descriptionEn", new JScrollPane(descEn)).add("lbl.descriptionAr", new JScrollPane(descAr))
            .add("lbl.category", category).add("lbl.tags", tagPanel).add("lbl.venue", venue).add("lbl.capacity", capacity).add("lbl.hostClub", club);
        timesRow.add(new JLabel(t("lbl.start"))); timesRow.add(start); timesRow.add(new JLabel(t("lbl.end"))); timesRow.add(end); timesRow.add(new JLabel("yyyy-MM-dd HH:mm"));
        form.add("lbl.when", timesRow);
        sessionTable = new DataTable<>(ctx, List.of(col("col.topic", s -> ctx.msg.name(s.topic())), col("col.starts", s -> ctx.msg.dateTime(s.start())), col("col.ends", s -> ctx.msg.time(s.end()))));
        sessionTable.setPreferredSize(new Dimension(420, 110));
        sessionBox.add(sessionTable, BorderLayout.CENTER);
        sessionBox.add(Ui.row(Ui.button(ctx, "btn.addSession", e -> editSession(null)), Ui.button(ctx, "btn.edit", e -> { if (sessionTable.selected() != null) editSession(sessionTable.selected()); }),
                Ui.button(ctx, "btn.remove", e -> { SessionData s = sessionTable.selected(); if (s != null) { sessions.remove(s); sessionTable.setRows(sessions); } })), BorderLayout.SOUTH);
        form.add("tab.sessions", sessionBox);
        form.add("lbl.regOpens", regOpens).add("lbl.regDeadline", regDeadline).add("lbl.cancelDeadline", cancelDeadline);
        form.add("lbl.poster", Ui.row(Ui.button(ctx, "btn.choosePoster", e -> choosePoster()), posterName, clearPoster));
        repeat.setText(t("lbl.repeat"));
        recurrence.add(repeat); recurrence.add(freq); recurrence.add(new JLabel(t("lbl.every"))); recurrence.add(interval);
        for (JCheckBox b : days.values()) recurrence.add(b);
        recurrence.add(new JLabel(t("lbl.until"))); recurrence.add(until); recurrence.add(new JLabel(t("lbl.orCount"))); recurrence.add(count);
        if (existing == null && ctx.isStaff()) form.add("lbl.recurrence", recurrence);
        type.addActionListener(e -> updateVisibility());
        JPanel south = Ui.row(Ui.button(ctx, "btn.checkAvailability", e -> checkAvailability()),
                Ui.button(ctx, existing == null ? "btn.saveDraft" : "btn.save", e -> save(false)));
        if (ctx.isStaff() && (existing == null || existing.status() == EventStatus.DRAFT)) south.add(Ui.button(ctx, "btn.saveAndPublish", e -> save(true)));
        south.add(Ui.button(ctx, "btn.cancel", e -> ctx.back()));
        add(Ui.title(t(existing == null ? (ctx.isStaff() ? "title.newEvent" : "title.newProposal") : "title.editEvent")), BorderLayout.NORTH);
        add(Ui.vscroll(form), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        updateVisibility();
    }

    private EventType selectedType() { return ((Ui.Item<EventType>) type.getSelectedItem()).value(); }

    private void updateVisibility() {
        boolean ws = selectedType() == EventType.WORKSHOP;
        form.setRowVisible(timesRow, !ws); form.setRowVisible(sessionBox, ws);
        form.setRowVisible(recurrence, selectedType() == EventType.GENERAL);
        form.revalidate();
    }

    @Override public void onShow() {
        ctx.run(this, () -> new Object[]{ctx.app.venues.list(ctx.session(), true), ctx.app.catalog.categories(ctx.session()), ctx.app.catalog.tags(ctx.session()),
                ctx.app.clubs.list(ctx.session(), true)}, r -> {
            category.addItem(new Ui.Item<>(null, "—")); venue.addItem(new Ui.Item<>(null, "—")); club.addItem(new Ui.Item<>(null, t("opt.noClub")));
            for (Venue v : (List<Venue>) r[0]) venue.addItem(new Ui.Item<>(v.getId(), ctx.msg.name(v.getName()) + " (" + v.getCapacity() + ")"));
            for (CatalogRepository.Item i : (List<CatalogRepository.Item>) r[1]) category.addItem(new Ui.Item<>(i.id(), ctx.msg.name(i.name())));
            for (CatalogRepository.Item i : (List<CatalogRepository.Item>) r[2]) { JCheckBox b = new JCheckBox(ctx.msg.name(i.name())); tagBoxes.put(i.id(), b); tagPanel.add(b); }
            for (var ci : (List<ClubInfo>) r[3]) {
                Club c = ci.club();
                if (ctx.isStaff() || ctx.officerClubs().contains(c.getId())) club.addItem(new Ui.Item<>(c.getId(), ctx.msg.name(c.getName())));
            }
            if (fixedClub != null) { select(club, fixedClub); club.setEnabled(false); }
            if (!ctx.isStaff() && existing != null) club.setEnabled(false);
            if (existing != null) fill(existing);
            ctx.orient(form); form.revalidate();
        });
    }

    private static void select(JComboBox<Ui.Item<Long>> box, Long id) {
        for (int i = 0; i < box.getItemCount(); i++) if (Objects.equals(box.getItemAt(i).value(), id)) { box.setSelectedIndex(i); return; }
    }

    private void fill(Event e) {
        for (int i = 0; i < type.getItemCount(); i++) if (type.getItemAt(i).value() == e.type()) type.setSelectedIndex(i);
        titleEn.setText(e.getTitle().en()); titleAr.setText(e.getTitle().ar()); descEn.setText(e.getDescription().en()); descAr.setText(e.getDescription().ar());
        select(category, e.getCategoryId()); select(venue, e.getVenueId()); select(club, e.getHostClubId());
        tagBoxes.forEach((id, b) -> b.setSelected(e.getTagIds().contains(id)));
        capacity.setText(String.valueOf(e.getCapacity()));
        if (!(e instanceof Workshop)) { start.setText(Fmt.format(e.getStartAt())); end.setText(Fmt.format(e.getEndAt())); }
        regOpens.setText(Fmt.format(e.getRegOpensAt())); regDeadline.setText(Fmt.format(e.getRegDeadlineAt())); cancelDeadline.setText(Fmt.format(e.getCancelDeadlineAt()));
        if (e instanceof Workshop w) { sessions.clear(); sessions.addAll(EventService.toData(w, true).sessions); sessionTable.setRows(sessions); }
        updateVisibility();
    }

    private void choosePoster() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG / JPEG", "png", "jpg", "jpeg"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            poster = Files.readAllBytes(fc.getSelectedFile().toPath());
            String n = fc.getSelectedFile().getName().toLowerCase(Locale.ROOT);
            posterType = n.endsWith(".png") ? "image/png" : "image/jpeg";
            posterName.setText(fc.getSelectedFile().getName());
        } catch (IOException ex) { ctx.error(this, new ValidationException("error.file.read")); }
    }

    private void editSession(SessionData existingSession) {
        JTextField tEn = new JTextField(existingSession == null ? "" : existingSession.topic().en(), 24), tAr = new JTextField(existingSession == null ? "" : existingSession.topic().ar(), 24);
        JTextField s = new JTextField(existingSession == null ? "" : Fmt.format(existingSession.start()), 14), e = new JTextField(existingSession == null ? "" : Fmt.format(existingSession.end()), 14);
        JComboBox<Ui.Item<Long>> v = new JComboBox<>();
        v.addItem(new Ui.Item<>(null, t("opt.workshopVenue")));
        for (int i = 1; i < venue.getItemCount(); i++) v.addItem(venue.getItemAt(i));
        if (existingSession != null) select(v, existingSession.venueId());
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.topicEn", tEn).add("lbl.topicAr", tAr).add("lbl.start", s).add("lbl.end", e).add("lbl.venue", v);
        Ui.ask(ctx, this, "title.session", f, () -> {
            Instant0 st = new Instant0(Fmt.parseDateTime(s.getText()), Fmt.parseDateTime(e.getText()));
            if (st.s == null || st.e == null) throw new ValidationException("error.event.timesRequired");
            new TimeSlot(st.s, st.e);
            SessionData sd = new SessionData(existingSession == null ? null : existingSession.id(), new LocalizedText(tEn.getText(), tAr.getText()), st.s, st.e, ((Ui.Item<Long>) v.getSelectedItem()).value());
            if (existingSession != null) sessions.remove(existingSession);
            sessions.add(sd); sessions.sort(Comparator.comparing(SessionData::start));
            sessionTable.setRows(sessions);
            return true;
        });
    }
    private record Instant0(java.time.Instant s, java.time.Instant e) {}

    private EventData read() {
        EventData d = new EventData();
        d.type = existing != null ? existing.type() : selectedType();
        d.title = new LocalizedText(titleEn.getText(), titleAr.getText()); d.description = new LocalizedText(descEn.getText(), descAr.getText());
        d.categoryId = ((Ui.Item<Long>) category.getSelectedItem()).value();
        d.venueId = ((Ui.Item<Long>) venue.getSelectedItem()).value();
        d.hostClubId = ((Ui.Item<Long>) club.getSelectedItem()).value();
        tagBoxes.forEach((id, b) -> { if (b.isSelected()) d.tagIds.add(id); });
        d.capacity = capacity.getText().isBlank() ? 0 : Fmt.parseInt(capacity.getText(), "error.event.capacity");
        if (d.type == EventType.WORKSHOP) d.sessions = new ArrayList<>(sessions);
        else { d.startAt = Fmt.parseDateTime(start.getText()); d.endAt = Fmt.parseDateTime(end.getText()); }
        d.regOpensAt = Fmt.parseDateTime(regOpens.getText()); d.regDeadlineAt = Fmt.parseDateTime(regDeadline.getText()); d.cancelDeadlineAt = Fmt.parseDateTime(cancelDeadline.getText());
        d.poster = poster; d.posterType = posterType; d.clearPoster = clearPoster.isSelected();
        return d;
    }

    private void checkAvailability() {
        final EventData d;
        try { d = read(); } catch (ValidationException ex) { ctx.error(this, ex); return; }
        if (d.venueId == null) { ctx.error(this, new ValidationException("error.event.venueRequired")); return; }
        List<TimeSlot> slots = new ArrayList<>();
        try {
            if (d.type == EventType.WORKSHOP) for (SessionData s : d.sessions) slots.add(new TimeSlot(s.start(), s.end()));
            else slots.add(new TimeSlot(d.startAt, d.endAt));
        } catch (RuntimeException ex) { ctx.error(this, new ValidationException("error.event.timesRequired")); return; }
        ctx.run(this, () -> ctx.app.venues.checkAvailability(ctx.session(), d.venueId, slots, existing == null ? null : existing.getId()), (List<Booking> c) -> {
            if (c.isEmpty()) ctx.info(this, "msg.venueAvailable"); else ctx.info(this, "error.venue.conflict", c);
        });
    }

    private void save(boolean publish) {
        final EventData d;
        try { d = read(); } catch (ValidationException ex) { ctx.error(this, ex); return; }
        if (existing != null) {
            ctx.run(this, () -> ctx.app.events.edit(ctx.session(), existing.getId(), existing.getVersion(), d), e -> { ctx.info(this, "msg.saved"); ctx.back(); });
            return;
        }
        if (repeat.isSelected() && d.type == EventType.GENERAL) { saveSeries(d, publish); return; }
        ctx.run(this, () -> {
            Event e = ctx.app.events.create(ctx.session(), d);
            if (publish) ctx.app.events.publish(ctx.session(), e.getId());
            return e;
        }, e -> { ctx.back(); ctx.go(() -> new EventDetailScreen(ctx, e.getId())); });
    }

    /** Previews the occurrences, lets Staff skip conflicting dates, then creates the series atomically (FR-REC-03, FR-REC-04). */
    private void saveSeries(EventData d, boolean publish) {
        final RecurrenceRule rule;
        try {
            Set<DayOfWeek> sel = new HashSet<>();
            days.forEach((k, b) -> { if (b.isSelected()) sel.add(k); });
            rule = new RecurrenceRule((RecurrenceRule.Frequency) freq.getSelectedItem(), Fmt.parseInt(interval.getText(), "error.recurrence.interval"), sel,
                    Fmt.parseLocalDate(until.getText()), count.getText().isBlank() ? null : Fmt.parseInt(count.getText(), "error.recurrence.count"));
        } catch (ValidationException ex) { ctx.error(this, ex); return; }
        ctx.run(this, () -> ctx.app.events.previewSeries(ctx.session(), d, rule), (List<SeriesOccurrence> plan) -> {
            JPanel p = new JPanel(new GridLayout(0, 1));
            Map<Integer, JCheckBox> skip = new LinkedHashMap<>();
            for (SeriesOccurrence o : plan) {
                JCheckBox b = new JCheckBox(o.index() + ". " + ctx.msg.arg(o.slot()) + (o.hasConflict() ? "  ⚠ " + ctx.t("msg.conflictWith", o.conflicts()) : ""), false);
                b.setSelected(o.hasConflict());
                skip.put(o.index(), b); p.add(b);
            }
            p.add(new JLabel(ctx.t("msg.skipHint")));
            boolean ok = Ui.ask(ctx, this, "title.seriesPreview", new JScrollPane(p), () -> {
                Set<Integer> s = new HashSet<>();
                skip.forEach((i, b) -> { if (b.isSelected()) s.add(i); });
                ctx.run(this, () -> ctx.app.events.createSeries(ctx.session(), d, rule, s, publish), l -> { ctx.info(this, "msg.seriesCreated", l.size()); ctx.back(); });
                return true;
            });
        });
    }
}

/** S15 management list of events in all states for Staff, and for officers their club's events (FR-LCY-10, S13 pending proposals). */
final class ManageEventsScreen extends Screen {
    private final Long clubId;
    private final boolean proposalsOnly;
    private final JTextField keyword = new JTextField(16);
    private final JComboBox<Ui.Item<EventStatus>> state = new JComboBox<>();
    private final DataTable<EventCard> table;

    public ManageEventsScreen(UiContext ctx) { this(ctx, false, null); }
    public ManageEventsScreen(UiContext ctx, boolean proposalsOnly) { this(ctx, proposalsOnly, null); }
    public ManageEventsScreen(UiContext ctx, long clubId) { this(ctx, false, clubId); }

    private ManageEventsScreen(UiContext ctx, boolean proposalsOnly, Long clubId) {
        super(ctx);
        this.proposalsOnly = proposalsOnly;
        this.clubId = clubId != null ? clubId : (ctx.isStaff() || ctx.officerClubs().isEmpty() ? null : ctx.officerClubs().get(0));
        table = new DataTable<>(ctx, List.of(
                col("col.title", c -> ctx.msg.name(c.event().getTitle())), col("col.type", c -> t("type." + c.event().type().name())),
                col("col.starts", c -> ctx.msg.dateTime(c.event().getStartAt())), col("col.status", c -> t("status." + c.event().status().name())),
                col("col.seats", c -> c.confirmed() + "/" + c.event().getCapacity() + (c.waitlisted() > 0 ? " (+" + c.waitlisted() + ")" : ""))));
        table.onActivate(c -> ctx.go(() -> new EventDetailScreen(ctx, c.event().getId())));
        state.addItem(new Ui.Item<>(null, t("opt.allStates")));
        for (EventStatus s : EventStatus.values()) state.addItem(new Ui.Item<>(s, t("status." + s.name())));
        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(Ui.title(t(proposalsOnly ? "title.proposals" : "title.manageEvents")));
        JPanel filter = Ui.row(new JLabel(t("lbl.search")), keyword);
        if (!proposalsOnly) { filter.add(new JLabel(t("lbl.state"))); filter.add(state); }
        filter.add(Ui.button(ctx, "btn.search", e -> load()));
        top.add(filter);
        add(top, BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        JPanel south = Ui.row(Ui.button(ctx, "btn.view", e -> { if (table.selected() != null) ctx.go(() -> new EventDetailScreen(ctx, table.selected().event().getId())); }));
        if (!proposalsOnly) {
            south.add(Ui.button(ctx, ctx.isStaff() ? "btn.newEvent" : "btn.newProposal", e -> ctx.go(() -> ctx.isStaff() ? new EventEditorScreen(ctx) : new EventEditorScreen(ctx, this.clubId))));
            if (ctx.isStaff()) south.add(Ui.button(ctx, "btn.catalog", e -> catalog()));
        }
        add(south, BorderLayout.SOUTH);
        keyword.addActionListener(e -> load());
    }

    @Override public void onShow() { load(); }

    private void load() {
        SearchCriteria c = new SearchCriteria();
        c.keyword = keyword.getText(); c.includePast = true; c.pageSize = 100; c.hostClubId = clubId;
        if (proposalsOnly) c.statuses = EnumSet.of(EventStatus.PENDING_APPROVAL);
        else { EventStatus s = ((Ui.Item<EventStatus>) state.getSelectedItem()).value(); if (s != null) c.statuses = EnumSet.of(s); }
        ctx.run(this, () -> ctx.app.search.manage(ctx.session(), c), p -> table.setRows(p.items()));
    }

    /** Staff maintain categories and tags (FR-EVT-07). */
    private void catalog() {
        JTextField en = new JTextField(14), ar = new JTextField(14);
        JComboBox<String> kind = new JComboBox<>(new String[]{t("lbl.category"), t("lbl.tag")});
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.type", kind).add("lbl.nameEn", en).add("lbl.nameAr", ar);
        Ui.ask(ctx, this, "title.catalog", f, () -> {
            LocalizedText n = new LocalizedText(en.getText(), ar.getText());
            if (kind.getSelectedIndex() == 0) ctx.app.catalog.addCategory(ctx.session(), n); else ctx.app.catalog.addTag(ctx.session(), n);
            ctx.info(this, "msg.saved");
            return true;
        });
    }
}
