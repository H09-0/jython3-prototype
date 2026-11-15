package org.example.jython3.ui;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.GraphicsEnvironment;
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
                var ev = new UiEvent("window.close", id, frame.getTitle(), System.currentTimeMillis()).toPyHandle();
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
            var ev = new UiEvent("button.click", id, button.getText(), System.currentTimeMillis()).toPyHandle();
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
            var ev = new UiEvent("textfield.change", id, text, System.currentTimeMillis()).toPyHandle();
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
        SwingPanel(String id) { this.id = id; }
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
