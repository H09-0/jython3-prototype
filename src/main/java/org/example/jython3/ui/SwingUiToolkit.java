package org.example.jython3.ui;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Swing EDT-backed {@link UiToolkit} for desktop integration.
 * Enforces Event Dispatch Thread affinity, capability checks, and deterministic
 * widget/listener/timer cleanup on {@link HostSession#close()}.
 */
public final class SwingUiToolkit implements UiToolkit {

    public static final class SwingEdtDispatcher implements UiDispatcher {
        @Override public boolean isUiThread() {
            return SwingUtilities.isEventDispatchThread();
        }

        @Override public void runLater(Runnable task) {
            SwingUtilities.invokeLater(task);
        }

        @Override public void runAndWait(Runnable task) {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting for Swing EDT");
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw PyErrors.fromJava(cause != null ? cause : e);
            }
        }
    }

    private final SwingEdtDispatcher dispatcher = new SwingEdtDispatcher();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override public UiDispatcher dispatcher() { return dispatcher; }

    @Override
    public UiWindow createWindow(HostSession session, String title, int width, int height) {
        session.require(HostCapability.UI, "create_window", title);
        dispatcher.assertUiThread("create_window");
        if (GraphicsEnvironment.isHeadless()) {
            throw PyErrors.error("RuntimeError", "SwingUiToolkit cannot create JFrame in headless JVM");
        }
        var win = new SwingWindow("swing-win-" + idSeq.getAndIncrement(), session, title, width, height);
        session.acquireWindowSlot(win, title);
        return win;
    }

    @Override
    public UiButton createButton(HostSession session, String text) {
        session.require(HostCapability.UI, "create_button", text);
        dispatcher.assertUiThread("create_button");
        var btn = new SwingButton("swing-btn-" + idSeq.getAndIncrement(), session, text);
        session.registerResource(btn::disposeListeners);
        return btn;
    }

    @Override
    public UiLabel createLabel(HostSession session, String text) {
        session.require(HostCapability.UI, "create_label", text);
        dispatcher.assertUiThread("create_label");
        return new SwingLabel("swing-lbl-" + idSeq.getAndIncrement(), text);
    }

    @Override
    public UiTextField createTextField(HostSession session, String text) {
        session.require(HostCapability.UI, "create_textfield", text);
        dispatcher.assertUiThread("create_textfield");
        var tf = new SwingTextField("swing-txt-" + idSeq.getAndIncrement(), session, text);
        session.registerResource(tf::disposeListeners);
        return tf;
    }

    @Override
    public UiPanel createPanel(HostSession session) {
        session.require(HostCapability.UI, "create_panel", "");
        dispatcher.assertUiThread("create_panel");
        return new SwingPanel("swing-pnl-" + idSeq.getAndIncrement());
    }

    @Override
    public UiCheckBox createCheckBox(HostSession session, String text, boolean checked) {
        session.require(HostCapability.UI, "create_checkbox", text);
        dispatcher.assertUiThread("create_checkbox");
        var cb = new SwingCheckBox("swing-chk-" + idSeq.getAndIncrement(), session, text, checked);
        session.registerResource(cb::disposeListeners);
        return cb;
    }

    @Override
    public UiProgressBar createProgressBar(HostSession session, double value, boolean indeterminate) {
        session.require(HostCapability.UI, "create_progressbar", "");
        dispatcher.assertUiThread("create_progressbar");
        return new SwingProgressBar("swing-prg-" + idSeq.getAndIncrement(), value, indeterminate);
    }

    @Override
    public UiListView createListView(HostSession session, List<String> items) {
        session.require(HostCapability.UI, "create_listview", "");
        dispatcher.assertUiThread("create_listview");
        var lv = new SwingListView("swing-lst-" + idSeq.getAndIncrement(), session, items);
        session.registerResource(lv::disposeListeners);
        return lv;
    }

    @Override
    public UiTimer createTimer(HostSession session, int intervalMs, PyObject callback) {
        session.require(HostCapability.TIMER, "create_timer", intervalMs + "ms");
        if (intervalMs < 1) throw PyErrors.error("ValueError", "timer interval must be >= 1 ms");
        var t = new SwingTimer("swing-tmr-" + idSeq.getAndIncrement(), session, intervalMs, callback);
        session.acquireTimerSlot(t, intervalMs + "ms");
        return t;
    }

    private interface SwingBacked {
        JComponent component();
    }

    private final class SwingWindow implements UiWindow {
        private final String id;
        private final HostSession session;
        private final JFrame frame;
        private final JPanel root = new JPanel();
        private final List<UiWidget> children = new ArrayList<>();
        private final List<PyObject> closeListeners = new CopyOnWriteArrayList<>();
        private boolean closed = false;

        SwingWindow(String id, HostSession session, String title, int width, int height) {
            this.id = id;
            this.session = session;
            this.frame = new JFrame(title);
            this.frame.setSize(width, height);
            this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            this.frame.setContentPane(root);
            this.frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    handleClosed();
                }
            });
        }

        private void handleClosed() {
            if (closed) return;
            closed = true;
            var snap = List.copyOf(closeListeners);
            closeListeners.clear();
            session.releaseWindowSlot(this);
            if (!session.isClosed() && !snap.isEmpty()) {
                var ev = new UiEvent("window.close", id, frame.getTitle(), System.currentTimeMillis()).toPyHandle(session);
                for (PyObject cb : snap) session.invokeCallback(cb, ev);
            }
        }

        @Override public String id() { return id; }
        @Override public String title() { return frame.getTitle(); }
        @Override public void setTitle(String title) { dispatcher.assertUiThread("window.setTitle"); frame.setTitle(title); }
        @Override public int width() { return frame.getWidth(); }
        @Override public int height() { return frame.getHeight(); }
        @Override public boolean isVisible() { return frame.isVisible() && !closed; }
        @Override public boolean isClosed() { return closed; }
        @Override public void show() { dispatcher.assertUiThread("window.show"); frame.setVisible(true); }

        @Override public void close() {
            if (closed) return;
            dispatcher.runLater(() -> {
                handleClosed();
                frame.dispose();
            });
        }

        @Override public void add(UiWidget child) {
            dispatcher.assertUiThread("window.add");
            if (child instanceof SwingBacked sb) {
                root.add(sb.component());
                children.add(child);
            }
        }

        @Override public List<UiWidget> children() { return List.copyOf(children); }

        @Override public void onClose(PyObject callback) {
            session.require(HostCapability.EVENTS, "window.on_close", id);
            session.checkListenerQuota(closeListeners.size(), id);
            closeListeners.add(callback);
        }

        @Override public int listenerCount() { return closeListeners.size(); }
    }

    private final class SwingButton implements UiButton, SwingBacked {
        private final String id;
        private final HostSession session;
        private final JButton button;
        private final List<PyObject> listeners = new CopyOnWriteArrayList<>();

        SwingButton(String id, HostSession session, String text) {
            this.id = id;
            this.session = session;
            this.button = new JButton(text);
            this.button.addActionListener(e -> click());
        }

        @Override public JComponent component() { return button; }
        @Override public String id() { return id; }
        @Override public String text() { return button.getText(); }
        @Override public void setText(String text) { dispatcher.assertUiThread("button.setText"); button.setText(text); }
        @Override public boolean isEnabled() { return button.isEnabled(); }
        @Override public void setEnabled(boolean enabled) { dispatcher.assertUiThread("button.setEnabled"); button.setEnabled(enabled); }

        @Override public void onClick(PyObject callback) {
            session.require(HostCapability.INPUT, "button.on_click", id);
            session.require(HostCapability.EVENTS, "button.on_click", id);
            session.checkListenerQuota(listeners.size(), id);
            listeners.add(callback);
        }

        @Override public void click() {
            if (!button.isEnabled()) return;
            var ev = new UiEvent("button.click", id, button.getText(), System.currentTimeMillis()).toPyHandle(session);
            for (PyObject cb : listeners) session.invokeCallback(cb, ev);
        }

        @Override public int listenerCount() { return listeners.size(); }
        void disposeListeners() {
            listeners.clear();
            for (ActionListener l : button.getActionListeners()) button.removeActionListener(l);
        }
    }

    private final class SwingLabel implements UiLabel, SwingBacked {
        private final String id;
        private final JLabel label;
        SwingLabel(String id, String text) { this.id = id; this.label = new JLabel(text); }
        @Override public JComponent component() { return label; }
        @Override public String id() { return id; }
        @Override public String text() { return label.getText(); }
        @Override public void setText(String text) { dispatcher.assertUiThread("label.setText"); label.setText(text); }
    }

    private final class SwingTextField implements UiTextField, SwingBacked {
        private final String id;
        private final HostSession session;
        private final JTextField field;
        private final List<PyObject> listeners = new CopyOnWriteArrayList<>();

        SwingTextField(String id, HostSession session, String text) {
            this.id = id;
            this.session = session;
            this.field = new JTextField(text);
        }

        @Override public JComponent component() { return field; }
        @Override public String id() { return id; }
        @Override public String text() { return field.getText(); }
        @Override public void setText(String text) {
            dispatcher.assertUiThread("textfield.setText");
            field.setText(text);
            var ev = new UiEvent("textfield.change", id, text, System.currentTimeMillis()).toPyHandle(session);
            for (PyObject cb : listeners) session.invokeCallback(cb, ev);
        }

        @Override public void onChange(PyObject callback) {
            session.require(HostCapability.INPUT, "textfield.on_change", id);
            session.require(HostCapability.EVENTS, "textfield.on_change", id);
            session.checkListenerQuota(listeners.size(), id);
            listeners.add(callback);
        }

        @Override public int listenerCount() { return listeners.size(); }
        void disposeListeners() { listeners.clear(); }
    }

    private final class SwingPanel implements UiPanel, SwingBacked {
        private final String id;
        private final JPanel panel = new JPanel();
        private final List<UiWidget> children = new ArrayList<>();
        private UiLayout layout = UiLayout.FLOW;
        private int spacing = 0;
        private int columns = 2;

        SwingPanel(String id) { this.id = id; applyLayout(); }

        private void applyLayout() {
            switch (layout) {
                case FLOW -> panel.setLayout(new FlowLayout(FlowLayout.LEFT, spacing, spacing));
                case VBOX -> panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                case HBOX -> panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
                case GRID -> panel.setLayout(new GridLayout(0, columns, spacing, spacing));
            }
            panel.revalidate();
        }

        @Override public JComponent component() { return panel; }
        @Override public String id() { return id; }
        @Override public void add(UiWidget child) {
            dispatcher.assertUiThread("panel.add");
            if (child instanceof SwingBacked sb) {
                panel.add(sb.component());
                children.add(child);
            }
        }
        @Override public List<UiWidget> children() { return List.copyOf(children); }
        @Override public UiLayout layout() { return layout; }
        @Override public void setLayout(UiLayout layout) {
            dispatcher.assertUiThread("panel.setLayout");
            this.layout = Objects.requireNonNull(layout, "layout");
            applyLayout();
        }
        @Override public int spacing() { return spacing; }
        @Override public void setSpacing(int spacing) {
            dispatcher.assertUiThread("panel.setSpacing");
            if (spacing < 0) throw PyErrors.error("ValueError", "spacing must be >= 0");
            this.spacing = spacing;
            applyLayout();
        }
        @Override public int columns() { return columns; }
        @Override public void setColumns(int columns) {
            dispatcher.assertUiThread("panel.setColumns");
            if (columns < 1) throw PyErrors.error("ValueError", "columns must be >= 1");
            this.columns = columns;
            applyLayout();
        }
    }

    private final class SwingCheckBox implements UiCheckBox, SwingBacked {
        private final String id;
        private final HostSession session;
        private final JCheckBox box;
        private final List<PyObject> listeners = new CopyOnWriteArrayList<>();

        SwingCheckBox(String id, HostSession session, String text, boolean checked) {
            this.id = id;
            this.session = session;
            this.box = new JCheckBox(text != null ? text : "", checked);
            this.box.addActionListener(e -> fireChanged());
        }

        private void fireChanged() {
            var ev = new UiEvent("checkbox.change", id, Boolean.toString(box.isSelected()), System.currentTimeMillis())
                    .toPyHandle(session);
            for (PyObject cb : listeners) session.invokeCallback(cb, ev);
        }

        @Override public JComponent component() { return box; }
        @Override public String id() { return id; }
        @Override public String text() { return box.getText(); }
        @Override public void setText(String text) { dispatcher.assertUiThread("checkbox.setText"); box.setText(text); }
        @Override public boolean isChecked() { return box.isSelected(); }
        @Override public void setChecked(boolean checked) {
            dispatcher.assertUiThread("checkbox.setChecked");
            if (box.isSelected() != checked) {
                box.setSelected(checked);
                fireChanged();
            }
        }
        @Override public void onChange(PyObject callback) {
            session.require(HostCapability.INPUT, "checkbox.on_change", id);
            session.require(HostCapability.EVENTS, "checkbox.on_change", id);
            session.checkListenerQuota(listeners.size(), id);
            listeners.add(callback);
        }
        @Override public int listenerCount() { return listeners.size(); }
        void disposeListeners() {
            listeners.clear();
            for (ActionListener l : box.getActionListeners()) box.removeActionListener(l);
        }
    }

    private final class SwingProgressBar implements UiProgressBar, SwingBacked {
        private final String id;
        private final JProgressBar bar = new JProgressBar(0, 1000);
        private double value;

        SwingProgressBar(String id, double value, boolean indeterminate) {
            this.id = id;
            setValueDirect(value);
            bar.setIndeterminate(indeterminate);
        }

        private void setValueDirect(double v) {
            if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
                throw PyErrors.error("ValueError", "progress value must be between 0.0 and 1.0");
            }
            this.value = v;
            bar.setValue((int) Math.round(v * 1000.0));
        }

        @Override public JComponent component() { return bar; }
        @Override public String id() { return id; }
        @Override public double value() { return value; }
        @Override public void setValue(double value) { dispatcher.assertUiThread("progressbar.setValue"); setValueDirect(value); }
        @Override public boolean isIndeterminate() { return bar.isIndeterminate(); }
        @Override public void setIndeterminate(boolean indeterminate) {
            dispatcher.assertUiThread("progressbar.setIndeterminate");
            bar.setIndeterminate(indeterminate);
        }
    }

    private final class SwingListView implements UiListView, SwingBacked {
        private final String id;
        private final HostSession session;
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private final List<PyObject> listeners = new CopyOnWriteArrayList<>();

        SwingListView(String id, HostSession session, List<String> items) {
            this.id = id;
            this.session = session;
            this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (items != null) items.forEach(model::addElement);
        }

        @Override public JComponent component() { return list; }
        @Override public String id() { return id; }
        @Override public List<String> items() {
            List<String> out = new ArrayList<>(model.size());
            for (int i = 0; i < model.size(); i++) out.add(model.get(i));
            return List.copyOf(out);
        }
        @Override public void setItems(List<String> items) {
            dispatcher.assertUiThread("listview.setItems");
            model.clear();
            if (items != null) items.forEach(model::addElement);
        }
        @Override public int selectedIndex() { return list.getSelectedIndex(); }
        @Override public void setSelectedIndex(int index) {
            dispatcher.assertUiThread("listview.setSelectedIndex");
            if (index < -1 || index >= model.size()) {
                throw PyErrors.error("IndexError", "list view index out of range: " + index);
            }
            if (index == -1) {
                list.clearSelection();
            } else {
                list.setSelectedIndex(index);
            }
            String val = index >= 0 ? model.get(index) : "";
            var ev = new UiEvent("listview.select", id, val, System.currentTimeMillis()).toPyHandle(session);
            for (PyObject cb : listeners) session.invokeCallback(cb, ev);
        }
        @Override public void onSelect(PyObject callback) {
            session.require(HostCapability.INPUT, "listview.on_select", id);
            session.require(HostCapability.EVENTS, "listview.on_select", id);
            session.checkListenerQuota(listeners.size(), id);
            listeners.add(callback);
        }
        @Override public int listenerCount() { return listeners.size(); }
        void disposeListeners() { listeners.clear(); }
    }

    private final class SwingTimer implements UiTimer {
        private final String id;
        private final HostSession session;
        private final int intervalMs;
        private final Timer timer;

        SwingTimer(String id, HostSession session, int intervalMs, PyObject callback) {
            this.id = id;
            this.session = session;
            this.intervalMs = intervalMs;
            this.timer = new Timer(intervalMs, e -> {
                if (session.isClosed()) { stop(); return; }
                try {
                    session.invokeCallback(callback);
                } catch (RuntimeException ex) {
                    stop();
                    session.recordCallbackFailure(id, ex);
                }
            });
        }

        @Override public String id() { return id; }
        @Override public int intervalMs() { return intervalMs; }
        @Override public boolean isRunning() { return timer.isRunning(); }
        @Override public void start() { if (!session.isClosed()) timer.start(); }
        @Override public void stop() { timer.stop(); }
    }
}
