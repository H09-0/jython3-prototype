package org.example.jython3.ui;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java, display-free {@link UiToolkit} for CI, server-side scripting, and
 * deterministic event-ordering tests.
 *
 * <p>Enforces UI-thread affinity via {@link #dispatcher()}, resource caps
 * ({@code maxWindows}, {@code maxTimers}, {@code maxListenersPerNode}),
 * capability checks, and complete cleanup when {@link HostSession#close()} or
 * {@link HostSession#reset()} runs.
 */
public final class HeadlessUiToolkit implements UiToolkit {

    private final AtomicLong idSeq = new AtomicLong(1);
    private final AtomicInteger openWindows = new AtomicInteger(0);
    private final AtomicInteger activeTimers = new AtomicInteger(0);
    private final List<UiEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
    private final HeadlessDispatcher dispatcher = new HeadlessDispatcher();

    public static final class HeadlessDispatcher implements UiDispatcher {
        private volatile Thread uiThread;

        public HeadlessDispatcher() {
            this.uiThread = Thread.currentThread();
        }

        public void setUiThread(Thread t) { this.uiThread = Objects.requireNonNull(t); }

        @Override public boolean isUiThread() { return Thread.currentThread() == uiThread; }

        @Override public void runLater(Runnable task) {
            Thread prev = uiThread;
            uiThread = Thread.currentThread();
            try {
                task.run();
            } finally {
                uiThread = prev;
            }
        }

        @Override public void runAndWait(Runnable task) {
            runLater(task);
        }
    }

    @Override public HeadlessDispatcher dispatcher() { return dispatcher; }

    public List<UiEvent> eventHistory() {
        synchronized (eventHistory) {
            return List.copyOf(eventHistory);
        }
    }

    public int openWindowCount() { return openWindows.get(); }
    public int activeTimerCount() { return activeTimers.get(); }

    @Override
    public UiWindow createWindow(HostSession session, String title, int width, int height) {
        session.require(HostCapability.UI, "create_window", title);
        dispatcher.assertUiThread("create_window");
        int maxW = session.capabilities().maxWindows();
        if (openWindows.get() >= maxW) {
            throw PyErrors.error("RuntimeError", "maximum open UI windows exceeded: " + maxW);
        }
        var win = new HeadlessWindow("win-" + idSeq.getAndIncrement(), session, title, width, height);
        openWindows.incrementAndGet();
        session.registerResource(win);
        return win;
    }

    @Override
    public UiButton createButton(HostSession session, String text) {
        session.require(HostCapability.UI, "create_button", text);
        dispatcher.assertUiThread("create_button");
        var btn = new HeadlessButton("btn-" + idSeq.getAndIncrement(), session, text);
        session.registerResource(btn::clearListeners);
        return btn;
    }

    @Override
    public UiLabel createLabel(HostSession session, String text) {
        session.require(HostCapability.UI, "create_label", text);
        dispatcher.assertUiThread("create_label");
        return new HeadlessLabel("lbl-" + idSeq.getAndIncrement(), text);
    }

    @Override
    public UiTextField createTextField(HostSession session, String text) {
        session.require(HostCapability.UI, "create_textfield", text);
        dispatcher.assertUiThread("create_textfield");
        var tf = new HeadlessTextField("txt-" + idSeq.getAndIncrement(), session, text);
        session.registerResource(tf::clearListeners);
        return tf;
    }

    @Override
    public UiPanel createPanel(HostSession session) {
        session.require(HostCapability.UI, "create_panel", "");
        dispatcher.assertUiThread("create_panel");
        return new HeadlessPanel("pnl-" + idSeq.getAndIncrement());
    }

    @Override
    public UiTimer createTimer(HostSession session, int intervalMs, PyObject callback) {
        session.require(HostCapability.TIMER, "create_timer", intervalMs + "ms");
        if (intervalMs < 1) {
            throw PyErrors.error("ValueError", "timer interval must be >= 1 ms");
        }
        int maxT = session.capabilities().maxTimers();
        if (activeTimers.get() >= maxT) {
            throw PyErrors.error("RuntimeError", "maximum active timers exceeded: " + maxT);
        }
        var timer = new HeadlessTimer("tmr-" + idSeq.getAndIncrement(), session, intervalMs, callback);
        session.registerResource(timer);
        return timer;
    }

    private void addListenerChecked(HostSession session, List<PyObject> listeners, PyObject cb, String target) {
        session.require(HostCapability.EVENTS, "attach_listener", target);
        int maxL = session.capabilities().maxListenersPerNode();
        if (listeners.size() >= maxL) {
            throw PyErrors.error("RuntimeError", "maximum listeners per UI node exceeded: " + maxL);
        }
        listeners.add(Objects.requireNonNull(cb, "callback"));
    }

    private void dispatchEvent(HostSession session, List<PyObject> listeners, UiEvent event) {
        eventHistory.add(event);
        PyObject evHandle = event.toPyHandle();
        for (PyObject cb : listeners) {
            session.invokeCallback(cb, evHandle);
        }
    }

    private final class HeadlessWindow implements UiWindow {
        private final String id;
        private final HostSession session;
        private String title;
        private final int width;
        private final int height;
        private boolean visible = false;
        private boolean closed = false;
        private final List<UiWidget> children = new ArrayList<>();
        private final List<PyObject> closeListeners = new CopyOnWriteArrayList<>();

        HeadlessWindow(String id, HostSession session, String title, int width, int height) {
            this.id = id;
            this.session = session;
            this.title = title;
            this.width = width;
            this.height = height;
        }

        @Override public String id() { return id; }
        @Override public String title() { return title; }
        @Override public void setTitle(String title) {
            dispatcher.assertUiThread("window.setTitle");
            ensureOpen();
            this.title = title;
        }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public boolean isVisible() { return visible && !closed; }
        @Override public boolean isClosed() { return closed; }

        @Override public void show() {
            dispatcher.assertUiThread("window.show");
            ensureOpen();
            this.visible = true;
            eventHistory.add(new UiEvent("window.show", id, title, System.currentTimeMillis()));
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            visible = false;
            openWindows.decrementAndGet();
            var snapshot = List.copyOf(closeListeners);
            closeListeners.clear();
            children.clear();
            session.unregisterResource(this);
            if (!session.isClosed() && !snapshot.isEmpty()) {
                dispatchEvent(session, snapshot, new UiEvent("window.close", id, title, System.currentTimeMillis()));
            }
        }

        @Override public void add(UiWidget child) {
            dispatcher.assertUiThread("window.add");
            ensureOpen();
            children.add(Objects.requireNonNull(child, "child"));
        }

        @Override public List<UiWidget> children() { return List.copyOf(children); }

        @Override public void onClose(PyObject callback) {
            ensureOpen();
            addListenerChecked(session, closeListeners, callback, id);
        }

        @Override public int listenerCount() { return closeListeners.size(); }

        private void ensureOpen() {
            if (closed) throw PyErrors.error("RuntimeError", "window '" + id + "' is closed");
        }
    }

    private final class HeadlessButton implements UiButton {
        private final String id;
        private final HostSession session;
        private String text;
        private boolean enabled = true;
        private final List<PyObject> clickListeners = new CopyOnWriteArrayList<>();

        HeadlessButton(String id, HostSession session, String text) {
            this.id = id;
            this.session = session;
            this.text = text;
        }

        @Override public String id() { return id; }
        @Override public String text() { return text; }
        @Override public void setText(String text) {
            dispatcher.assertUiThread("button.setText");
            this.text = text;
        }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(boolean enabled) {
            dispatcher.assertUiThread("button.setEnabled");
            this.enabled = enabled;
        }

        @Override public void onClick(PyObject callback) {
            session.require(HostCapability.INPUT, "on_click", id);
            addListenerChecked(session, clickListeners, callback, id);
        }

        @Override public void click() {
            if (!enabled) return;
            dispatcher.runAndWait(() ->
                    dispatchEvent(session, clickListeners, new UiEvent("button.click", id, text, System.currentTimeMillis())));
        }

        @Override public int listenerCount() { return clickListeners.size(); }

        void clearListeners() { clickListeners.clear(); }
    }

    private final class HeadlessLabel implements UiLabel {
        private final String id;
        private String text;

        HeadlessLabel(String id, String text) {
            this.id = id;
            this.text = text;
        }

        @Override public String id() { return id; }
        @Override public String text() { return text; }
        @Override public void setText(String text) {
            dispatcher.assertUiThread("label.setText");
            this.text = text;
        }
    }

    private final class HeadlessTextField implements UiTextField {
        private final String id;
        private final HostSession session;
        private String text;
        private final List<PyObject> changeListeners = new CopyOnWriteArrayList<>();

        HeadlessTextField(String id, HostSession session, String text) {
            this.id = id;
            this.session = session;
            this.text = text != null ? text : "";
        }

        @Override public String id() { return id; }
        @Override public String text() { return text; }
        @Override public void setText(String text) {
            dispatcher.assertUiThread("textfield.setText");
            this.text = text != null ? text : "";
            dispatchEvent(session, changeListeners, new UiEvent("textfield.change", id, this.text, System.currentTimeMillis()));
        }

        @Override public void onChange(PyObject callback) {
            session.require(HostCapability.INPUT, "on_change", id);
            addListenerChecked(session, changeListeners, callback, id);
        }

        @Override public int listenerCount() { return changeListeners.size(); }

        void clearListeners() { changeListeners.clear(); }
    }

    private final class HeadlessPanel implements UiPanel {
        private final String id;
        private final List<UiWidget> children = new ArrayList<>();

        HeadlessPanel(String id) { this.id = id; }

        @Override public String id() { return id; }
        @Override public void add(UiWidget child) {
            dispatcher.assertUiThread("panel.add");
            children.add(Objects.requireNonNull(child, "child"));
        }
        @Override public List<UiWidget> children() { return List.copyOf(children); }
    }

    private final class HeadlessTimer implements UiTimer {
        private final String id;
        private final HostSession session;
        private final int intervalMs;
        private final PyObject callback;
        private ScheduledExecutorService exec;
        private ScheduledFuture<?> future;
        private volatile boolean running = false;

        HeadlessTimer(String id, HostSession session, int intervalMs, PyObject callback) {
            this.id = id;
            this.session = session;
            this.intervalMs = intervalMs;
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        @Override public String id() { return id; }
        @Override public int intervalMs() { return intervalMs; }
        @Override public boolean isRunning() { return running; }

        @Override
        public synchronized void start() {
            if (running || session.isClosed()) return;
            running = true;
            activeTimers.incrementAndGet();
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jython3-timer-" + id);
                t.setDaemon(true);
                return t;
            });
            future = exec.scheduleAtFixedRate(() -> {
                if (!running || session.isClosed()) {
                    stop();
                    return;
                }
                dispatcher.runAndWait(() -> {
                    if (running && !session.isClosed()) {
                        try {
                            session.invokeCallback(callback);
                        } catch (RuntimeException ex) {
                            stop();
                        }
                    }
                });
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void stop() {
            if (running) {
                running = false;
                activeTimers.decrementAndGet();
            }
            if (future != null) {
                future.cancel(false);
                future = null;
            }
            if (exec != null) {
                exec.shutdownNow();
                exec = null;
            }
        }
    }
}
