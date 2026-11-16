package uems;

import java.awt.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Cursor;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.time.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;

// MODULE 3 - USER INTERFACE BASE (shared): localized messages, input formats, UiContext, Screen, DataTable, builders and the main window.

// ======== ui ========

/** Localized texts and formats for the current UI language (NFR-USA-02, FR-LOC-01..06). All dates are shown in Gulf Standard Time. */
final class Messages {
    private static final String BASE = "i18n.messages";
    private Language lang = Language.EN;
    private ResourceBundle bundle = load(Language.EN);
    private final ResourceBundle english = load(Language.EN);
    private DateTimeFormatter dateTime, date, time;

    public Messages() { setLanguage(Language.EN); }

    private static ResourceBundle load(Language l) {
        Locale loc = l == Language.AR ? Locale.forLanguageTag("ar") : Locale.ROOT;
        return ResourceBundle.getBundle(BASE, loc, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public Language language() { return lang; }
    public boolean isRtl() { return lang == Language.AR; }

    public void setLanguage(Language l) {
        this.lang = l;
        this.bundle = load(l);
        Locale loc = l == Language.AR ? Locale.forLanguageTag("ar-u-nu-latn") : Locale.ENGLISH;   // Western digits in both languages
        dateTime = DateTimeFormatter.ofPattern(l == Language.AR ? "EEEE d MMMM yyyy، HH:mm" : "EEE d MMM yyyy, HH:mm", loc).withZone(Gst.ZONE);
        date = DateTimeFormatter.ofPattern(l == Language.AR ? "EEEE d MMMM yyyy" : "EEE d MMM yyyy", loc).withZone(Gst.ZONE);
        time = DateTimeFormatter.ofPattern("HH:mm", loc).withZone(Gst.ZONE);
    }

    /** Looks up a key (English fallback, then the key itself) and formats the arguments. */
    public String t(String key, Object... args) {
        String pattern = bundle.containsKey(key) ? bundle.getString(key) : english.containsKey(key) ? english.getString(key) : key;
        Object[] a = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < a.length; i++) a[i] = arg(args[i]);
        return new MessageFormat(pattern).format(a);
    }

    public boolean has(String key) { return bundle.containsKey(key) || english.containsKey(key); }

    /** Converts message arguments to display text. */
    public String arg(Object o) {
        if (o == null) return "";
        if (o instanceof LocalizedText t) return t.get(lang);
        if (o instanceof Instant i) return dateTime.format(i);
        if (o instanceof TimeSlot s) return dateTime.format(s.start()) + " – " + time.format(s.end());
        if (o instanceof String s) {
            LocalizedText lt = LocalizedText.decode(s);
            if (lt != null) return lt.get(lang);
            Instant in = Params.decodeInstant(s);
            if (in != null) return dateTime.format(in);
            String k = Params.decodeKey(s);
            if (k != null) return t(k);
            return s;
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object x : l) { if (sb.length() > 0) sb.append(", "); sb.append(x instanceof Booking b ? b.eventTitle().get(lang) + " (" + arg(b.slot()) + ")" : String.valueOf(x)); }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    public String dateTime(Instant i) { return i == null ? "" : dateTime.format(i); }
    public String date(Instant i) { return i == null ? "" : date.format(i); }
    public String time(Instant i) { return i == null ? "" : time.format(i); }
    public String name(LocalizedText t) { return t == null ? "" : t.get(lang); }
}

/** Parsing of date and time text fields entered in Gulf Standard Time (yyyy-MM-dd HH:mm). */
final class Fmt {
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private Fmt() {}

    /** Blank gives null; otherwise parses yyyy-MM-dd HH:mm in GST. */
    public static Instant parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s.trim(), DATE_TIME).atZone(Gst.ZONE).toInstant(); }
        catch (DateTimeParseException e) { throw new ValidationException("error.input.datetime"); }
    }
    /** Blank gives null; otherwise parses yyyy-MM-dd as the start of that day in GST. */
    public static Instant parseDay(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim());
            return (endOfDay ? d.plusDays(1) : d).atStartOfDay(Gst.ZONE).toInstant();
        } catch (DateTimeParseException e) { throw new ValidationException("error.input.date"); }
    }
    public static LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { throw new ValidationException("error.input.date"); }
    }
    public static String format(Instant i) { return i == null ? "" : DATE_TIME.format(i.atZone(Gst.ZONE)); }
    public static int parseInt(String s, String errorKey) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { throw new ValidationException(errorKey); }
    }
}

/** Everything a screen needs: the application services, the session, texts and helpers (Facade for the presentation layer). */
final class UiContext {
    public final AppContext app;
    public final Messages msg = new Messages();
    private UserSession session;
    private List<Long> officerClubs = List.of();
    MainFrame frame;

    public UiContext(AppContext app) { this.app = app; }

    public UserSession session() { return session; }
    public boolean isGuest() { return session == null; }
    public boolean isStudent() { return session != null && session.getAccountType() == AccountType.STUDENT; }
    public boolean isStaff() { return session != null && session.getAccountType() == AccountType.STAFF; }
    public boolean isAdmin() { return session != null && session.getAccountType() == AccountType.ADMIN; }
    public boolean isOfficer() { return !officerClubs.isEmpty(); }
    public List<Long> officerClubs() { return officerClubs; }
    public MainFrame frame() { return frame; }

    void setSession(UserSession s) {
        this.session = s;
        this.officerClubs = List.of();
        if (s != null) msg.setLanguage(s.getLanguage());
    }
    void setOfficerClubs(List<Long> ids) { this.officerClubs = ids; }

    public String t(String key, Object... args) { return msg.t(key, args); }

    /** Changes the language at once (FR-LOC-02): texts, direction and the stored preference. */
    public void setLanguage(Language l) {
        msg.setLanguage(l);
        if (session != null) { session.setLanguage(l); run(() -> { app.users.setLanguage(session, l); return null; }, x -> { }); }
        if (frame != null) frame.rebuild();
    }

    /** Applies the reading direction to a component tree (NFR-USA-03). */
    public void orient(Component c) {
        c.applyComponentOrientation(msg.isRtl() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
    }

    /** Runs the work off the UI thread, then the continuation on it; errors are shown as localized messages (NFR-PRF-04). */
    public <T> void run(Callable<T> work, Consumer<T> ok) { run(null, work, ok); }

    public <T> void run(Component owner, Callable<T> work, Consumer<T> ok) {
        Component target = owner != null ? owner : frame;
        if (target != null) target.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                if (target != null) target.setCursor(Cursor.getDefaultCursor());
                try { ok.accept(get()); }
                catch (java.util.concurrent.ExecutionException e) { error(owner, e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }.execute();
    }

    /** Shows an error as a localized dialog; an expired session returns to the login screen (FR-AUT-03). */
    public void error(Component owner, Throwable ex) {
        if (ex instanceof SessionExpiredException) { if (frame != null) frame.sessionExpired(); return; }
        String text;
        if (ex instanceof UemsException u) text = t(u.getMessageKey(), u.getArgs());
        else { text = t("error.unexpected"); ex.printStackTrace(); }
        JOptionPane.showMessageDialog(owner != null ? owner : frame, text, t("title.error"), JOptionPane.ERROR_MESSAGE);
    }

    public void info(Component owner, String key, Object... args) {
        JOptionPane.showMessageDialog(owner != null ? owner : frame, t(key, args), t("title.info"), JOptionPane.INFORMATION_MESSAGE);
    }

    public boolean confirm(Component owner, String key, Object... args) {
        Object[] opts = {t("btn.yes"), t("btn.no")};
        return JOptionPane.showOptionDialog(owner != null ? owner : frame, t(key, args), t("title.confirm"), JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, opts, opts[1]) == 0;
    }

    /** Asks for a text such as a mandatory reason; returns null when cancelled. */
    public String prompt(Component owner, String key) {
        JTextArea area = new JTextArea(4, 30);
        area.setLineWrap(true); area.setWrapStyleWord(true);
        JPanel p = new JPanel(new java.awt.BorderLayout(4, 4));
        p.add(new JLabel(t(key)), java.awt.BorderLayout.NORTH);
        p.add(new JScrollPane(area), java.awt.BorderLayout.CENTER);
        orient(p);
        Object[] opts = {t("btn.ok"), t("btn.cancel")};
        int r = JOptionPane.showOptionDialog(owner != null ? owner : frame, p, t("title.input"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        return r == 0 ? area.getText().trim() : null;
    }

    /** Navigation helpers. */
    public void go(java.util.function.Supplier<Screen> factory) { frame.show(factory); }
    /** Opens a top-level screen from the navigation (clears the back stack). */
    public void open(java.util.function.Supplier<Screen> factory) { frame.open(factory); }
    public void back() { frame.back(); }
}

/** A full-page view. Screens only show data and call application services; they hold no business rules (NFR-MNT-01). */
abstract class Screen extends JPanel {
    protected final UiContext ctx;

    protected Screen(UiContext ctx) {
        super(new BorderLayout(8, 8));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    /** Called when the screen becomes visible; start loading data here. */
    public void onShow() { }

    protected String t(String key, Object... args) { return ctx.t(key, args); }
}

/** A read-only table bound to a list of row objects with one text extractor per column. */
class DataTable<T> extends JScrollPane {
    /** Column definition: header key and cell text. */
    public record Col<T>(String key, Function<T, String> text) {}

    private final List<Col<T>> cols;
    private final UiContext ctx;
    private List<T> rows = new ArrayList<>();
    private final JTable table = new JTable();
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.size(); }
        @Override public String getColumnName(int c) { return ctx.t(cols.get(c).key()); }
        @Override public Object getValueAt(int r, int c) { return cols.get(c).text().apply(rows.get(r)); }
    };

    public DataTable(UiContext ctx, List<Col<T>> cols) {
        this.ctx = ctx; this.cols = cols;
        table.setModel(model);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        setViewportView(table);
    }

    public static <T> Col<T> col(String key, Function<T, String> f) { return new Col<>(key, f); }

    public void setRows(List<T> r) { this.rows = new ArrayList<>(r); model.fireTableDataChanged(); pack(); }

    /** Sizes each column to its widest header or cell (at most 480 px) so dates and names are not cut off. */
    private void pack() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        for (int c = 0; c < table.getColumnCount(); c++) {
            var col = table.getColumnModel().getColumn(c);
            var hr = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(table, col.getHeaderValue(), false, false, -1, c);
            int w = hr.getPreferredSize().width;
            for (int r = 0; r < Math.min(table.getRowCount(), 200); r++) w = Math.max(w, table.getCellRenderer(r, c).getTableCellRendererComponent(table, table.getValueAt(r, c), false, false, r, c).getPreferredSize().width);
            col.setPreferredWidth(Math.min(480, w + 24));
        }
    }
    public List<T> rows() { return rows; }
    public JTable table() { return table; }

    /** The selected row object or null. */
    public T selected() {
        int v = table.getSelectedRow();
        return v < 0 ? null : rows.get(table.convertRowIndexToModel(v));
    }
    public List<T> selectedAll() {
        List<T> l = new ArrayList<>();
        for (int v : table.getSelectedRows()) l.add(rows.get(table.convertRowIndexToModel(v)));
        return l;
    }

    /** Calls the handler on double-click or Enter. */
    public DataTable<T> onActivate(Consumer<T> h) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && selected() != null) h.accept(selected()); }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "activate");
        table.getActionMap().put("activate", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (selected() != null) h.accept(selected()); } });
        return this;
    }
}

/** Small Swing builders that keep screens short. */
final class Ui {
    private Ui() {}

    public static JButton button(UiContext ctx, String key, ActionListener a) {
        JButton b = new JButton(ctx.t(key));
        b.addActionListener(a);
        return b;
    }

    /** A horizontal row of buttons/components. */
    public static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
        for (Component c : cs) p.add(c);
        return p;
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 20f));
        return l;
    }
    public static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    public static JTextArea textArea(int rows, int cols) {
        JTextArea a = new JTextArea(rows, cols);
        a.setLineWrap(true); a.setWrapStyleWord(true);
        return a;
    }
    public static JTextArea readOnlyText(String text) {
        JTextArea a = textArea(1, 40);
        a.setText(text); a.setEditable(false); a.setBorder(null);
        a.setBackground(UIManager.getColor("Panel.background"));
        return a;
    }

    /** A scroll pane whose content follows the viewport width, so wide content never needs a horizontal scrollbar. */
    public static JScrollPane vscroll(JComponent content) {
        class Tracker extends JPanel implements Scrollable {
            Tracker() { super(new BorderLayout()); add(content, BorderLayout.NORTH); }
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
            public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 80; }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() { return false; }
        }
        JScrollPane sp = new JScrollPane(new Tracker());
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Two-column label/field form. */
    public static final class Form extends JPanel implements Scrollable {
        private final UiContext ctx;
        private int row;
        private final java.util.Map<Component, Component> labels = new java.util.HashMap<>();
        public Form(UiContext ctx) { super(new GridBagLayout()); this.ctx = ctx; }

        /** Shows or hides a field together with its label. */
        public void setRowVisible(Component field, boolean visible) {
            field.setVisible(visible);
            Component l = labels.get(field);
            if (l != null) l.setVisible(visible);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 80; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }

        public Form add(String labelKey, Component field) {
            GridBagConstraints l = new GridBagConstraints();
            l.gridx = 0; l.gridy = row; l.anchor = GridBagConstraints.FIRST_LINE_START; l.insets = new Insets(4, 4, 4, 10);
            JLabel label = new JLabel(labelKey == null ? "" : ctx.t(labelKey));
            labels.put(field, label);
            if (field instanceof JScrollPane sp && sp.getViewport().getView() instanceof JTextArea ta) {   // wrapped text areas need an explicit height
                int h = ta.getRows() * ta.getFontMetrics(ta.getFont()).getHeight() + 12;
                sp.setPreferredSize(new Dimension(200, h)); sp.setMinimumSize(new Dimension(100, h));
            }
            super.add(label, l);
            GridBagConstraints f = new GridBagConstraints();
            f.gridx = 1; f.gridy = row++; f.weightx = 1; f.fill = GridBagConstraints.HORIZONTAL; f.insets = new Insets(4, 0, 4, 4);
            super.add(field, f);
            return this;
        }
    }

    /**
     * Shows a modal dialog with OK/Cancel. The handler returns true to close; on exception the error is shown and the dialog stays open.
     * Returns true when confirmed.
     */
    public static boolean ask(UiContext ctx, Component owner, String titleKey, JComponent content, Callable<Boolean> onOk) {
        Window w = owner == null ? ctx.frame() : SwingUtilities.getWindowAncestor(owner);
        JDialog d = new JDialog(w, ctx.t(titleKey), Dialog.ModalityType.APPLICATION_MODAL);
        boolean[] ok = {false};
        JButton okB = new JButton(ctx.t("btn.ok")), cancel = new JButton(ctx.t("btn.cancel"));
        okB.addActionListener(e -> {
            try { if (onOk == null || onOk.call()) { ok[0] = true; d.dispose(); } }
            catch (Exception ex) { ctx.error(d, ex); }
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 6));
        buttons.add(okB); buttons.add(cancel);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        root.add(content, BorderLayout.CENTER); root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(okB);
        ctx.orient(root);
        d.pack();
        d.setMinimumSize(new Dimension(Math.max(d.getWidth(), 380), d.getHeight()));
        d.setLocationRelativeTo(w);
        d.setVisible(true);
        return ok[0];
    }

    /** Shows a non-modal-result dialog with a single Close button (for lists and reports). */
    public static void show(UiContext ctx, Component owner, String titleKey, JComponent content, int w, int h) {
        Window win = owner == null ? ctx.frame() : SwingUtilities.getWindowAncestor(owner);
        JDialog d = new JDialog(win, ctx.t(titleKey), Dialog.ModalityType.APPLICATION_MODAL);
        JButton close = new JButton(ctx.t("btn.close"));
        close.addActionListener(e -> d.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        south.add(close);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        root.add(content, BorderLayout.CENTER); root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        ctx.orient(root);
        d.setSize(w, h);
        d.setLocationRelativeTo(win);
        d.setVisible(true);
    }

    /** Combo box item with a display text. */
    public record Item<T>(T value, String text) { @Override public String toString() { return text; } }
}

/** The application window: top bar, role-based navigation and the current screen (UC-01, FR-LOC-02, NFR-USA-04). */
final class MainFrame extends JFrame {
    private final UiContext ctx;
    private final JPanel top = new JPanel(new BorderLayout());
    private final JPanel nav = new JPanel();
    private final JPanel center = new JPanel(new BorderLayout());
    private final Deque<Supplier<Screen>> stack = new ArrayDeque<>();
    private JButton bell;
    private javax.swing.Timer badgeTimer, schedulerTimer, idleTimer;

    public MainFrame(UiContext ctx) {
        super("UEMS");
        this.ctx = ctx;
        ctx.frame = this;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 760);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(top, BorderLayout.NORTH);
        add(nav, BorderLayout.LINE_START);
        add(center, BorderLayout.CENTER);
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> touch(), AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        idleTimer = new javax.swing.Timer(15_000, e -> checkIdle());
        idleTimer.start();
        showLogin();
    }

    // ------------------------------------------------------------ navigation
    /** Pushes a screen (drill-down); Back returns to the previous one. */
    public void show(Supplier<Screen> factory) { stack.push(factory); display(factory); }
    /** Shows a top-level screen and clears the back stack. */
    public void open(Supplier<Screen> factory) { stack.clear(); show(factory); }
    public void back() { if (stack.size() > 1) { stack.pop(); display(stack.peek()); } }

    private void display(Supplier<Screen> factory) {
        Screen s = factory.get();
        center.removeAll();
        center.add(s, BorderLayout.CENTER);
        ctx.orient(this.getContentPane());
        center.revalidate(); center.repaint();
        s.onShow();
    }

    /** Rebuilds texts and direction after a language change, keeping the current screen. */
    public void rebuild() {
        Supplier<Screen> cur = stack.peek();
        buildChrome();
        if (cur != null) display(cur);
        ctx.orient(getContentPane());
        revalidate(); repaint();
    }

    // ------------------------------------------------------------ login / logout
    public void showLogin() {
        stopTimers();
        ctx.setSession(null);
        buildChrome();
        open(() -> new LoginScreen(ctx));
    }

    /** Called by the login screen with the new session. */
    public void setSessionAndContinue(UserSession s) {
        ctx.setSession(s);
        loggedIn();
    }

    /** Called after a forced password change: reload the session-dependent chrome and go home. */
    public void passwordChanged() { loggedIn(); }

    private void loggedIn() {
        if (ctx.session().isMustChangePassword()) {          // FR-AUT-08: only the profile page is usable until the password is changed
            ctx.setOfficerClubs(List.of());
            buildChrome();
            startTimers();
            open(() -> new ProfileScreen(ctx, true));
            return;
        }
        ctx.run(() -> ctx.app.clubs.officerClubIds(ctx.session()), ids -> {
            ctx.setOfficerClubs(ids);
            buildChrome();
            startTimers();
            open(() -> new HomeScreen(ctx));
        });
    }

    public void logout() {
        if (ctx.session() != null) ctx.app.auth.logout(ctx.session());
        showLogin();
    }

    public void sessionExpired() {
        if (ctx.session() == null) return;
        ctx.setSession(null);
        stopTimers();
        showLoginWithNotice();
    }

    private void showLoginWithNotice() {
        buildChrome();
        open(() -> new LoginScreen(ctx));
        JOptionPane.showMessageDialog(this, ctx.t("error.session.expired"), ctx.t("title.info"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void touch() {
        if (ctx.session() == null) return;
        try { ctx.app.sessions.validate(ctx.session()); } catch (SessionExpiredException ignored) { }
    }

    private void checkIdle() {
        if (ctx.session() != null && !ctx.app.sessions.isActive(ctx.session())) sessionExpired();
    }

    // ------------------------------------------------------------ timers (scheduler tick and notification badge)
    private void startTimers() {
        stopTimers();
        ctx.run(() -> { try { ctx.app.scheduler.tick(); } catch (RuntimeException e) { e.printStackTrace(); } return null; }, x -> { });
        schedulerTimer = new javax.swing.Timer(60_000, e -> new Thread(() -> { try { ctx.app.scheduler.tick(); } catch (RuntimeException ex) { ex.printStackTrace(); } }, "uems-scheduler").start());
        schedulerTimer.start();
        badgeTimer = new javax.swing.Timer(30_000, e -> refreshBadge());
        badgeTimer.start();
        refreshBadge();
    }

    private void stopTimers() {
        if (schedulerTimer != null) schedulerTimer.stop();
        if (badgeTimer != null) badgeTimer.stop();
    }

    /** Updates the unread count on the bell (polled every 30 s, FR-NTF-06). */
    public void refreshBadge() {
        if (ctx.session() == null || bell == null) return;
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() { return ctx.app.notifications.unreadCount(ctx.session()); }
            @Override protected void done() {
                try { int n = get(); bell.setText(ctx.t("nav.notifications") + (n > 0 ? " (" + n + ")" : "")); bell.setFont(bell.getFont().deriveFont(n > 0 ? Font.BOLD : Font.PLAIN)); }
                catch (Exception ignored) { }
            }
        }.execute();
    }

    // ------------------------------------------------------------ chrome
    private void buildChrome() {
        top.removeAll(); nav.removeAll();
        JLabel app = Ui.title(ctx.t("app.title"));
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        top.add(app, BorderLayout.LINE_START);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        JButton lang = new JButton(ctx.msg.language() == Language.EN ? "العربية" : "English");
        lang.addActionListener(e -> ctx.setLanguage(ctx.msg.language() == Language.EN ? Language.AR : Language.EN));
        right.add(lang);
        if (ctx.session() != null) {
            bell = Ui.button(ctx, "nav.notifications", e -> ctx.open(() -> new NotificationsScreen(ctx)));
            right.add(bell);
            right.add(new JLabel(ctx.msg.name(ctx.session().getDisplayName()) + " (" + ctx.t("accountType." + ctx.session().getAccountType().name()) + ")"));
            right.add(Ui.button(ctx, "btn.logout", e -> logout()));
        }
        top.add(right, BorderLayout.LINE_END);
        if (ctx.session() != null) buildNav();
        nav.setVisible(ctx.session() != null);
        ctx.orient(getContentPane());
        top.revalidate(); nav.revalidate(); top.repaint(); nav.repaint();
    }

    private void navButton(String key, Supplier<Screen> screen) {
        JButton b = new JButton(ctx.t(key));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        b.addActionListener(e -> open(screen));
        nav.add(b); nav.add(Box.createVerticalStrut(4));
    }

    private void buildNav() {
        if (ctx.session().isMustChangePassword()) { navButton("nav.profile", () -> new ProfileScreen(ctx, true)); return; }
        navButton("nav.home", () -> new HomeScreen(ctx));
        navButton("nav.browse", () -> new BrowseScreen(ctx));
        if (ctx.isStudent()) {
            navButton("nav.myRegistrations", () -> new MyRegistrationsScreen(ctx));
            navButton("nav.clubs", () -> new ClubsScreen(ctx));
        }
        if (ctx.isOfficer()) for (long id : ctx.officerClubs()) navButton("nav.myClub", () -> new ClubManageScreen(ctx, id));
        if (ctx.isOfficer()) navButton("nav.myEvents", () -> new ManageEventsScreen(ctx));
        if (ctx.isStaff()) {
            navButton("nav.manageEvents", () -> new ManageEventsScreen(ctx));
            navButton("nav.proposals", () -> new ManageEventsScreen(ctx, true));
            navButton("nav.clubsAdmin", () -> new ClubsAdminScreen(ctx));
            navButton("nav.myRegistrations", () -> new MyRegistrationsScreen(ctx));
        }
        if (ctx.isAdmin()) {
            navButton("nav.users", () -> new UsersScreen(ctx));
            navButton("nav.venues", () -> new VenuesScreen(ctx));
            navButton("nav.audit", () -> new AuditScreen(ctx));
            navButton("nav.myRegistrations", () -> new MyRegistrationsScreen(ctx));
        }
        navButton("nav.profile", () -> new ProfileScreen(ctx, false));
    }
}
