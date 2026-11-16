package uems;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Locale;
import javax.swing.*;

import static uems.DataTable.col;

// MODULE 4 - CLUBS, WORKSHOPS, COMPETITIONS AND DISCOVERY (screens)
// Swing screens for: browse, home, clubs, club management, discussion and feedback.

/** UC-03: search, filter, sort and page through events; also the public list for guests (S02, S04). */
final class BrowseScreen extends Screen {
    private final JTextField keyword = new JTextField(18), from = new JTextField(9), to = new JTextField(9);
    private final JComboBox<Ui.Item<EventType>> type = new JComboBox<>();
    private final JComboBox<Ui.Item<Long>> category = new JComboBox<>();
    private final JCheckBox openOnly = new JCheckBox(), seats = new JCheckBox(), past = new JCheckBox();
    private final JComboBox<Ui.Item<SearchCriteria.Sort>> sort = new JComboBox<>();
    private final JLabel pageLabel = new JLabel(" ");
    private final JButton prev, next;
    private final DataTable<EventCard> table;
    private final Map<Long, String> venueNames = new HashMap<>();
    private int page;
    private int pageCount = 1;

    public BrowseScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(
                col("col.title", c -> ctx.msg.name(c.event().getTitle())),
                col("col.type", c -> t("type." + c.event().type().name())),
                col("col.starts", c -> ctx.msg.dateTime(c.event().getStartAt())),
                col("col.venue", c -> venueNames.getOrDefault(c.event().getVenueId(), "")),
                col("col.seats", this::seatText),
                col("col.status", c -> t("status." + c.event().status().name())),
                col("col.rating", c -> c.ratingCount() == 0 ? "-" : String.format(Locale.ROOT, "%.1f (%d)", c.averageRating(), c.ratingCount()))));
        table.onActivate(c -> open(c));
        type.addItem(new Ui.Item<>(null, t("opt.allTypes")));
        for (EventType et : EventType.values()) type.addItem(new Ui.Item<>(et, t("type." + et.name())));
        category.addItem(new Ui.Item<>(null, t("opt.allCategories")));
        sort.addItem(new Ui.Item<>(SearchCriteria.Sort.DATE, t("opt.sortDate")));
        sort.addItem(new Ui.Item<>(SearchCriteria.Sort.TITLE, t("opt.sortTitle")));
        sort.addItem(new Ui.Item<>(SearchCriteria.Sort.POPULARITY, t("opt.sortPopularity")));
        openOnly.setText(t("lbl.openOnly")); seats.setText(t("lbl.seatsAvailable")); past.setText(t("lbl.includePast"));
        from.setToolTipText("yyyy-MM-dd"); to.setToolTipText("yyyy-MM-dd");

        JPanel filters = new JPanel(new GridLayout(0, 1, 0, 2));
        filters.add(Ui.row(Ui.title(t("title.browse")), new JLabel(ctx.isGuest() ? t("msg.guestHint") : "")));
        filters.add(Ui.row(new JLabel(t("lbl.search")), keyword, new JLabel(t("lbl.type")), type, new JLabel(t("lbl.category")), category));
        filters.add(Ui.row(new JLabel(t("lbl.from")), from, new JLabel(t("lbl.to")), to, new JLabel(t("lbl.sort")), sort, openOnly, seats, past,
                Ui.button(ctx, "btn.search", e -> { page = 0; search(); })));
        add(filters, BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        prev = Ui.button(ctx, "btn.prev", e -> { if (page > 0) { page--; search(); } });
        next = Ui.button(ctx, "btn.next", e -> { if (page + 1 < pageCount) { page++; search(); } });
        JPanel south = Ui.row(prev, pageLabel, next, Ui.button(ctx, "btn.view", e -> { if (table.selected() != null) open(table.selected()); }));
        if (ctx.isGuest()) south.add(Ui.button(ctx, "btn.backToLogin", e -> ctx.frame().showLogin()));
        add(south, BorderLayout.SOUTH);
        keyword.addActionListener(e -> { page = 0; search(); });
    }

    private String seatText(EventCard c) {
        if (c.event().status() == EventStatus.CANCELLED) return "-";
        String s = c.confirmed() + "/" + c.event().getCapacity();
        return c.waitlisted() > 0 ? s + " (+" + c.waitlisted() + ")" : s;
    }

    private void open(EventCard c) { ctx.go(() -> new EventDetailScreen(ctx, c.event().getId())); }

    @Override public void onShow() {
        ctx.run(() -> {
            List<Venue> v = ctx.isGuest() ? ctx.app.venues.publicList() : ctx.app.venues.list(ctx.session(), false);
            List<CatalogRepository.Item> cats = ctx.isGuest() ? ctx.app.catalog.publicCategories() : ctx.app.catalog.categories(ctx.session());
            return new Object[]{v, cats};
        }, r -> {
            @SuppressWarnings("unchecked") List<Venue> v = (List<Venue>) r[0];
            @SuppressWarnings("unchecked") List<CatalogRepository.Item> cats = (List<CatalogRepository.Item>) r[1];
            for (Venue x : v) venueNames.put(x.getId(), ctx.msg.name(x.getName()));
            for (CatalogRepository.Item i : cats) category.addItem(new Ui.Item<>(i.id(), ctx.msg.name(i.name())));
            search();
        });
    }

    private void search() {
        final SearchCriteria c;
        try {
            c = new SearchCriteria();
            c.keyword = keyword.getText();
            c.type = ((Ui.Item<EventType>) type.getSelectedItem()).value();
            c.categoryId = ((Ui.Item<Long>) category.getSelectedItem()).value();
            c.from = Fmt.parseDay(from.getText(), false);
            c.to = Fmt.parseDay(to.getText(), true);
            c.openOnly = openOnly.isSelected(); c.seatsAvailableOnly = seats.isSelected(); c.includePast = past.isSelected();
            c.sort = ((Ui.Item<SearchCriteria.Sort>) sort.getSelectedItem()).value();
            c.page = page;
        } catch (UemsException e) { ctx.error(this, e); return; }
        ctx.run(this, () -> ctx.app.search.search(ctx.session(), c), (Page<EventCard> p) -> {
            table.setRows(p.items());
            pageCount = p.pageCount();
            pageLabel.setText(t("msg.pageOf", p.page() + 1, pageCount, p.total()));
            prev.setEnabled(page > 0); next.setEnabled(page + 1 < pageCount);
        });
    }
}

/** S03 home: recommendations and next registrations for students; shortcuts and a welcome for other roles (UC-04). */
final class HomeScreen extends Screen {
    private final DataTable<Recommendation> recs;
    private final DataTable<MyRegistration> next;

    public HomeScreen(UiContext ctx) {
        super(ctx);
        add(Ui.title(t("msg.welcome", ctx.msg.name(ctx.session().getDisplayName()))), BorderLayout.NORTH);
        recs = new DataTable<>(ctx, List.of(
                col("col.title", r -> ctx.msg.name(r.event().getTitle())),
                col("col.starts", r -> ctx.msg.dateTime(r.event().getStartAt())),
                col("col.why", r -> r.reason() == null ? "" : t(r.reason().key(), r.reason().arg()))));
        recs.onActivate(r -> ctx.go(() -> new EventDetailScreen(ctx, r.event().getId())));
        next = new DataTable<>(ctx, List.of(
                col("col.title", r -> ctx.msg.name(r.event().getTitle())),
                col("col.starts", r -> ctx.msg.dateTime(r.event().getStartAt())),
                col("col.status", r -> t("regStatus." + r.registration().status().name()))));
        next.onActivate(r -> ctx.go(() -> new EventDetailScreen(ctx, r.event().getId())));
        JPanel body = new JPanel(new GridLayout(1, 0, 12, 12));
        if (ctx.isStudent()) {
            body.add(box("title.recommended", recs));
            body.add(box("title.nextEvents", next));
        } else {
            JPanel p = new JPanel(new BorderLayout());
            p.add(Ui.readOnlyText(t(ctx.isStaff() ? "msg.homeStaff" : ctx.isAdmin() ? "msg.homeAdmin" : "msg.homeOther")), BorderLayout.NORTH);
            body.add(p);
        }
        add(body, BorderLayout.CENTER);
    }

    private JPanel box(String titleKey, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(Ui.bold(t(titleKey)), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    @Override public void onShow() {
        if (!ctx.isStudent()) return;
        ctx.run(() -> ctx.app.recommendations.recommend(ctx.session()), recs::setRows);
        ctx.run(() -> {
            java.time.Instant now = java.time.Instant.now();
            return ctx.app.registrations.findMine(ctx.session()).stream()
                    .filter(r -> r.registration().status() != RegistrationStatus.CANCELLED && r.event().getEndAt() != null && r.event().getEndAt().isAfter(now)
                            && r.event().status() != EventStatus.CANCELLED)
                    .sorted(java.util.Comparator.comparing(r -> r.event().getStartAt())).limit(5).toList();
        }, next::setRows);
    }
}

/** S07 clubs: browse, request to join, leave (UC-10, FR-CLB-03, FR-CLB-05, FR-CLB-08). */
final class ClubsScreen extends Screen {
    private final DataTable<ClubInfo> table;

    public ClubsScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(
                col("col.name", c -> ctx.msg.name(c.club().getName())), col("col.members", c -> String.valueOf(c.members())),
                col("col.myStatus", c -> c.myStatus() == null ? "" : c.myStatus() == MembershipStatus.ACTIVE ? t("role." + c.myRole().name()) : t("memStatus." + c.myStatus().name()))));
        table.onActivate(this::details);
        add(Ui.title(t("title.clubs")), BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.details", e -> { if (table.selected() != null) details(table.selected()); }),
                Ui.button(ctx, "btn.requestJoin", e -> { ClubInfo c = table.selected(); if (c != null) ctx.run(this, () -> { ctx.app.clubs.requestJoin(ctx.session(), c.club().getId()); return null; }, x -> { ctx.info(this, "msg.joinRequested"); load(); }); }),
                Ui.button(ctx, "btn.leaveClub", e -> { ClubInfo c = table.selected(); if (c != null && ctx.confirm(this, "msg.confirmLeave")) ctx.run(this, () -> { ctx.app.clubs.leave(ctx.session(), c.club().getId()); return null; }, x -> load()); })), BorderLayout.SOUTH);
    }

    @Override public void onShow() { load(); }
    private void load() { ctx.run(this, () -> ctx.app.clubs.list(ctx.session(), true), table::setRows); }

    private void details(ClubInfo c) {
        ctx.run(this, () -> ctx.app.clubs.details(ctx.session(), c.club().getId()), (ClubDetails d) -> {
            JPanel p = new JPanel(new BorderLayout(6, 6));
            JPanel top = new JPanel(new GridLayout(0, 1));
            top.add(Ui.title(ctx.msg.name(d.club().getName())));
            top.add(Ui.readOnlyText(ctx.msg.name(d.club().getDescription())));
            StringBuilder off = new StringBuilder();
            d.officers().forEach(o -> off.append(off.length() > 0 ? ", " : "").append(ctx.msg.name(o.name())));
            top.add(new JLabel(t("lbl.officers") + ": " + off + "   |   " + t("col.members") + ": " + d.memberCount()));
            p.add(top, BorderLayout.NORTH);
            DataTable<Event> ev = new DataTable<>(ctx, List.of(col("col.title", e -> ctx.msg.name(e.getTitle())), col("col.starts", e -> ctx.msg.dateTime(e.getStartAt())), col("col.status", e -> t("status." + e.status().name()))));
            ev.setRows(d.events());
            ev.onActivate(e -> ctx.go(() -> new EventDetailScreen(ctx, e.getId())));
            p.add(ev, BorderLayout.CENTER);
            Ui.show(ctx, this, "title.clubDetails", p, 700, 460);
        });
    }
}

/** S11 officer view of one club: members, join requests, announcements, own events (UC-11, FR-CLB-04, FR-CLB-06, FR-CLB-09). */
final class ClubManageScreen extends Screen {
    private final long clubId;
    private final DataTable<MemberRow> members, requests;
    private final JTextField search = new JTextField(14);
    private final JLabel heading = Ui.title(" ");

    public ClubManageScreen(UiContext ctx, long clubId) {
        super(ctx);
        this.clubId = clubId;
        List<DataTable.Col<MemberRow>> cols = List.of(col("col.universityId", m -> m.universityId()), col("col.name", m -> ctx.msg.name(m.name())),
                col("col.role", m -> t("role." + m.membership().getRole().name())), col("col.joined", m -> ctx.msg.date(m.membership().getJoinedAt())));
        members = new DataTable<>(ctx, cols);
        requests = new DataTable<>(ctx, List.of(col("col.universityId", m -> m.universityId()), col("col.name", m -> ctx.msg.name(m.name())), col("col.requested", m -> ctx.msg.date(m.membership().getRequestedAt()))));
        JPanel mp = new JPanel(new BorderLayout(4, 4));
        mp.add(Ui.row(new JLabel(t("lbl.search")), search, Ui.button(ctx, "btn.search", e -> loadMembers()), Ui.button(ctx, "btn.removeMember", e -> removeMember())), BorderLayout.NORTH);
        mp.add(members, BorderLayout.CENTER);
        JPanel rp = new JPanel(new BorderLayout(4, 4));
        rp.add(requests, BorderLayout.CENTER);
        rp.add(Ui.row(Ui.button(ctx, "btn.approve", e -> decide(true)), Ui.button(ctx, "btn.reject", e -> decide(false))), BorderLayout.SOUTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.add(t("tab.members"), mp); tabs.add(t("tab.joinRequests"), rp);
        add(heading, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.announceMembers", e -> announce()),
                Ui.button(ctx, "btn.clubEvents", e -> ctx.go(() -> new ManageEventsScreen(ctx, clubId))),
                Ui.button(ctx, "btn.newProposal", e -> ctx.go(() -> new EventEditorScreen(ctx, clubId)))), BorderLayout.SOUTH);
        search.addActionListener(e -> loadMembers());
    }

    @Override public void onShow() {
        ctx.run(this, () -> ctx.app.clubs.details(ctx.session(), clubId), d -> heading.setText(ctx.msg.name(d.club().getName())));
        loadMembers(); loadRequests();
    }
    private void loadMembers() { ctx.run(this, () -> ctx.app.clubs.members(ctx.session(), clubId, search.getText()), members::setRows); }
    private void loadRequests() { ctx.run(this, () -> ctx.app.clubs.pendingRequests(ctx.session(), clubId), requests::setRows); }

    private void removeMember() {
        MemberRow m = members.selected();
        if (m != null && ctx.confirm(this, "msg.confirmRemoveMember")) ctx.run(this, () -> { ctx.app.clubs.removeMember(ctx.session(), clubId, m.membership().getUserId()); return null; }, x -> loadMembers());
    }

    private void decide(boolean approve) {
        MemberRow m = requests.selected();
        if (m == null) return;
        String reason = approve ? null : ctx.prompt(this, "lbl.reasonOptional");
        if (!approve && reason == null) return;
        ctx.run(this, () -> { ctx.app.clubs.decide(ctx.session(), m.membership().getId(), approve, reason); return null; }, x -> { loadRequests(); loadMembers(); });
    }

    private void announce() {
        JTextField sEn = new JTextField(30), sAr = new JTextField(30);
        JTextArea mEn = Ui.textArea(3, 30), mAr = Ui.textArea(3, 30);
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.subjectEn", sEn).add("lbl.subjectAr", sAr).add("lbl.messageEn", new JScrollPane(mEn)).add("lbl.messageAr", new JScrollPane(mAr));
        Ui.ask(ctx, this, "title.announce", f, () -> {
            int n = ctx.app.clubs.announce(ctx.session(), clubId, new LocalizedText(sEn.getText(), sAr.getText()), new LocalizedText(mEn.getText(), mAr.getText()));
            ctx.info(this, "msg.announcementSent", n);
            return true;
        });
    }
}

/** Staff club administration: create, edit, deactivate clubs and manage officers (UC-11, FR-CLB-01, FR-CLB-02). */
final class ClubsAdminScreen extends Screen {
    private final DataTable<ClubInfo> table;

    public ClubsAdminScreen(UiContext ctx) {
        super(ctx);
        table = new DataTable<>(ctx, List.of(col("col.name", c -> ctx.msg.name(c.club().getName())), col("col.members", c -> String.valueOf(c.members())),
                col("col.status", c -> t(c.club().isActive() ? "status.active" : "status.inactive"))));
        add(Ui.title(t("title.clubsAdmin")), BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        add(Ui.row(Ui.button(ctx, "btn.newClub", e -> edit(null)), Ui.button(ctx, "btn.edit", e -> { if (table.selected() != null) edit(table.selected().club()); }),
                Ui.button(ctx, "btn.officers", e -> { if (table.selected() != null) officers(table.selected().club()); }),
                Ui.button(ctx, "btn.deactivate", e -> { ClubInfo c = table.selected(); if (c != null && ctx.confirm(this, "msg.confirmDeactivate")) ctx.run(this, () -> { ctx.app.clubs.deactivate(ctx.session(), c.club().getId()); return null; }, x -> load()); })), BorderLayout.SOUTH);
    }

    @Override public void onShow() { load(); }
    private void load() { ctx.run(this, () -> ctx.app.clubs.list(ctx.session(), false), table::setRows); }

    private void edit(Club existing) {
        JTextField nEn = new JTextField(existing == null ? "" : existing.getName().en(), 28), nAr = new JTextField(existing == null ? "" : existing.getName().ar(), 28);
        JTextArea dEn = Ui.textArea(3, 28), dAr = Ui.textArea(3, 28);
        JTextField officer = new JTextField(14);
        if (existing != null) { dEn.setText(existing.getDescription().en()); dAr.setText(existing.getDescription().ar()); }
        Ui.Form f = new Ui.Form(ctx);
        f.add("lbl.nameEn", nEn).add("lbl.nameAr", nAr).add("lbl.descriptionEn", new JScrollPane(dEn)).add("lbl.descriptionAr", new JScrollPane(dAr));
        if (existing == null) f.add("lbl.firstOfficerId", officer);
        Ui.ask(ctx, this, existing == null ? "title.newClub" : "title.editClub", f, () -> {
            Club c = existing == null ? new Club() : existing;
            c.setName(new LocalizedText(nEn.getText(), nAr.getText())); c.setDescription(new LocalizedText(dEn.getText(), dAr.getText()));
            if (existing == null) { long officerId = ctx.app.users.lookupForClub(ctx.session(), officer.getText()).getId(); ctx.app.clubs.create(ctx.session(), c, officerId); }
            else ctx.app.clubs.update(ctx.session(), c);
            load();
            return true;
        });
    }

    private void officers(Club club) {
        DataTable<MemberRow> t = new DataTable<>(ctx, List.of(col("col.universityId", m -> m.universityId()), col("col.name", m -> ctx.msg.name(m.name()))));
        JTextField id = new JTextField(12);
        Runnable load = () -> ctx.run(this, () -> ctx.app.clubs.members(ctx.session(), club.getId(), "").stream().filter(m -> m.membership().getRole() == MembershipRole.OFFICER).toList(), t::setRows);
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(t, BorderLayout.CENTER);
        p.add(Ui.row(new JLabel(ctx.t("lbl.universityId")), id,
                Ui.button(ctx, "btn.appoint", e -> ctx.run(this, () -> { ctx.app.clubs.appointOfficer(ctx.session(), club.getId(), ctx.app.users.lookupForClub(ctx.session(), id.getText()).getId()); return null; }, x -> { id.setText(""); load.run(); })),
                Ui.button(ctx, "btn.removeOfficer", e -> { MemberRow m = t.selected(); if (m != null) ctx.run(this, () -> { ctx.app.clubs.removeOfficer(ctx.session(), club.getId(), m.membership().getUserId()); return null; }, x -> load.run()); })), BorderLayout.SOUTH);
        load.run();
        Ui.show(ctx, this, "title.officers", p, 600, 380);
    }
}

/** Threaded event discussion with reply, edit, delete and moderation (UC-15, FR-DSC-01..07). */
final class DiscussionPanel extends JPanel {
    private final UiContext ctx;
    private final long eventId;
    private final JPanel list = new JPanel();
    private final JTextArea input = Ui.textArea(3, 40);
    private final JLabel replyInfo = new JLabel(" ");
    private Long replyTo;
    private boolean readOnly;

    DiscussionPanel(UiContext ctx, long eventId) {
        super(new BorderLayout(6, 6));
        this.ctx = ctx; this.eventId = eventId;
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(replyInfo, BorderLayout.NORTH);
        south.add(new JScrollPane(input), BorderLayout.CENTER);
        south.add(Ui.row(Ui.button(ctx, "btn.post", e -> post()), Ui.button(ctx, "btn.cancelReply", e -> { replyTo = null; replyInfo.setText(" "); })), BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
        reload();
    }

    private void reload() {
        ctx.run(this, () -> new Object[]{ctx.app.discussion.list(ctx.session(), eventId), ctx.app.discussion.isReadOnly(ctx.session(), eventId)}, r -> {
            @SuppressWarnings("unchecked") List<Comment> all = (List<Comment>) r[0];
            readOnly = (Boolean) r[1];
            render(all);
        });
    }

    private void render(List<Comment> all) {
        list.removeAll();
        if (all.isEmpty()) list.add(new JLabel(ctx.t("msg.noComments")));
        for (Comment c : all) if (c.getParentId() == null) {
            list.add(entry(c, 0));
            for (Comment r : all) if (c.getId() == (r.getParentId() == null ? -1 : r.getParentId())) list.add(entry(r, 1));
        }
        input.setEnabled(!readOnly);
        if (readOnly) replyInfo.setText(ctx.t("msg.discussionReadOnly"));
        ctx.orient(list);
        list.revalidate(); list.repaint();
    }

    private JPanel entry(Comment c, int level) {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(2, level * 28, 2, 0), BorderFactory.createLineBorder(Color.LIGHT_GRAY)));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        String badge = c.getAuthorType() == AccountType.STAFF ? " [" + ctx.t("badge.staff") + "]" : c.isAuthorIsOfficer() ? " [" + ctx.t("badge.officer") + "]" : "";
        String head = ctx.msg.name(c.getAuthorName()) + badge + "  " + ctx.msg.dateTime(c.getCreatedAt()) + (c.isEdited() ? "  (" + ctx.t("msg.edited") + ")" : "");
        p.add(Ui.bold(head), BorderLayout.NORTH);
        String body = c.isRemoved()
                ? ctx.t(DiscussionService.REMOVED_BY_AUTHOR.equals(c.getRemovedReason()) ? "msg.removedByAuthor" : "msg.removedByModerator")
                : c.getBody();
        p.add(Ui.readOnlyText(body), BorderLayout.CENTER);
        if (!c.isRemoved()) {
            JPanel b = Ui.row();
            if (c.getParentId() == null && !readOnly) b.add(Ui.button(ctx, "btn.reply", e -> { replyTo = c.getId(); replyInfo.setText(ctx.t("msg.replyingTo", ctx.msg.name(c.getAuthorName()))); input.requestFocus(); }));
            if (c.getUserId() == ctx.session().getUserId()) {
                if (!readOnly) b.add(Ui.button(ctx, "btn.edit", e -> edit(c)));
                b.add(Ui.button(ctx, "btn.delete", e -> { if (ctx.confirm(this, "msg.confirmDeleteComment")) ctx.run(this, () -> { ctx.app.discussion.deleteOwn(ctx.session(), c.getId()); return null; }, x -> reload()); }));
            }
            if (ctx.isStaff()) b.add(Ui.button(ctx, "btn.remove", e -> {
                String reason = ctx.prompt(this, "lbl.reason");
                if (reason != null) ctx.run(this, () -> { ctx.app.discussion.moderate(ctx.session(), c.getId(), reason); return null; }, x -> reload());
            }));
            p.add(b, BorderLayout.SOUTH);
        }
        return p;
    }

    private void edit(Comment c) {
        JTextArea a = Ui.textArea(4, 40); a.setText(c.getBody());
        Ui.ask(ctx, this, "title.editComment", new JScrollPane(a), () -> {
            ctx.app.discussion.edit(ctx.session(), c.getId(), a.getText());
            reload();
            return true;
        });
    }

    private void post() {
        String text = input.getText();
        Long parent = replyTo;
        ctx.run(this, () -> ctx.app.discussion.post(ctx.session(), eventId, text, parent), c -> { input.setText(""); replyTo = null; replyInfo.setText(" "); reload(); });
    }
}

/** Rating summary for everybody; submit or edit feedback for eligible students; comments for organizers (UC-15). */
final class FeedbackPanel extends JPanel {
    private final UiContext ctx;
    private final long eventId;

    FeedbackPanel(UiContext ctx, long eventId) {
        super(new BorderLayout(6, 6));
        this.ctx = ctx; this.eventId = eventId;
        reload();
    }

    private void reload() {
        ctx.run(this, () -> new Object[]{ctx.app.feedback.summary(ctx.session(), eventId), ctx.app.feedback.status(ctx.session(), eventId), ctx.app.feedback.mine(ctx.session(), eventId).orElse(null)}, r ->
                render((FeedbackSummary) r[0], (FeedbackService.Status) r[1], (Feedback) r[2]));
    }

    private void render(FeedbackSummary s, FeedbackService.Status status, Feedback mine) {
        removeAll();
        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(Ui.bold(s.count() == 0 ? ctx.t("msg.noFeedback") : ctx.t("msg.avgRating", String.format(Locale.ROOT, "%.1f", s.average()), s.count())));
        for (int i = 5; i >= 1; i--) top.add(new JLabel(i + " ★  " + "█".repeat(Math.min(40, s.distribution()[i])) + " " + s.distribution()[i]));
        add(top, BorderLayout.NORTH);
        if (!s.comments().isEmpty()) {
            JPanel l = new JPanel(); l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
            for (Feedback f : s.comments()) l.add(Ui.readOnlyText(f.getRating() + " ★  " + (s.namesVisible() ? ctx.msg.name(f.getAuthorName()) + ": " : "") + f.getComment()));
            add(new JScrollPane(l), BorderLayout.CENTER);
        }
        if (status == FeedbackService.Status.CAN_SUBMIT || status == FeedbackService.Status.CAN_EDIT) {
            JComboBox<Integer> rating = new JComboBox<>(new Integer[]{5, 4, 3, 2, 1});
            JTextArea comment = Ui.textArea(3, 40);
            if (mine != null) { rating.setSelectedItem(mine.getRating()); comment.setText(mine.getComment() == null ? "" : mine.getComment()); }
            JPanel form = new JPanel(new BorderLayout(4, 4));
            form.add(Ui.row(new JLabel(ctx.t("lbl.yourRating")), rating), BorderLayout.NORTH);
            form.add(new JScrollPane(comment), BorderLayout.CENTER);
            boolean edit = status == FeedbackService.Status.CAN_EDIT;
            form.add(Ui.row(Ui.button(ctx, edit ? "btn.updateFeedback" : "btn.submitFeedback", e ->
                    ctx.run(this, () -> edit ? ctx.app.feedback.edit(ctx.session(), eventId, (Integer) rating.getSelectedItem(), comment.getText())
                                              : ctx.app.feedback.submit(ctx.session(), eventId, (Integer) rating.getSelectedItem(), comment.getText()), f -> reload()))), BorderLayout.SOUTH);
            add(form, BorderLayout.SOUTH);
        } else if (status == FeedbackService.Status.SUBMITTED_LOCKED) add(new JLabel(ctx.t("msg.feedbackLocked")), BorderLayout.SOUTH);
        ctx.orient(this);
        revalidate(); repaint();
    }
}
