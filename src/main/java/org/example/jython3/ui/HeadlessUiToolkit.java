package org.example.jython3.ui;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-Java, display-free {@link UiToolkit} for CI, server-side scripting, and
 * deterministic event-ordering tests.
 *
 * <p>Thread ownership of {@link HeadlessDispatcher} is immutable once created:
 * {@code runLater}/{@code runAndWait} never transfer UI-thread ownership to arbitrary
 * caller threads.
 */
public final class HeadlessUiToolkit implements UiToolkit, AutoCloseable {

    public static final class HeadlessDispatcher implements UiDispatcher, AutoCloseable {
        private final Thread uiThread;
        private final ExecutorService dedicatedExecutor;
        private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

        /** Binds UI-thread ownership immutably to the constructing thread; off-thread posts queue for {@link #pumpEvents()}. */
        public HeadlessDispatcher() {
            this.uiThread = Thread.currentThread();
            this.dedicatedExecutor = null;
        }

        private HeadlessDispatcher(ExecutorService executor, Thread dedicatedThread) {
            this.dedicatedExecutor = Objects.requireNonNull(executor);
            this.uiThread = Objects.requireNonNull(dedicatedThread);
        }

        /** Creates a dispatcher backed by a dedicated, single-threaded UI executor. */
        public static HeadlessDispatcher dedicatedThread() {
            ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jython3-headless-ui");
                t.setDaemon(true);
                return t;
            });
            var threadRef = new AtomicReference<Thread>();
            try {
                exec.submit(() -> threadRef.set(Thread.currentThread())).get();
            } catch (Exception e) {
                exec.shutdownNow();
                throw new IllegalStateException("Failed to initialize headless UI thread", e);
            }
            return new HeadlessDispatcher(exec, threadRef.get());
        }

        @Override public boolean isUiThread() { return Thread.currentThread() == uiThread; }

        @Override public void runLater(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (dedicatedExecutor != null) {
                dedicatedExecutor.execute(task);
            } else {
                pendingTasks.add(task);
            }
        }

        @Override public void runAndWait(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (isUiThread()) {
                task.run();
                return;
            }
            if (dedicatedExecutor == null) {
                throw PyErrors.error("RuntimeError",
                        "cannot runAndWait synchronously onto creator-owned HeadlessDispatcher from non-UI thread '"
                                + Thread.currentThread().getName() + "'; use dedicatedThread() or pumpEvents()");
            }
            var latch = new CountDownLatch(1);
            var err = new AtomicReference<RuntimeException>();
            dedicatedExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException re) {
                    err.set(re);
                } finally {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw PyErrors.error("TimeoutError", "timed out waiting for headless UI thread");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting for headless UI thread");
            }
            if (err.get() != null) throw err.get();
        }

        /** Drains queued {@link #runLater(Runnable)} tasks on the owning UI thread. */
        public int pumpEvents() {
            assertUiThread("pumpEvents");
            int count = 0;
            Runnable next;
            while ((next = pendingTasks.poll()) != null) {
                next.run();
                count++;
            }
            return count;
        }

        @Override public void close() {
            pendingTasks.clear();
            if (dedicatedExecutor != null) {
                dedicatedExecutor.shutdownNow();
            }
        }
    }

    private final AtomicLong idSeq = new AtomicLong(1);
    private final AtomicInteger openWindows = new AtomicInteger(0);
    private final AtomicInteger activeTimers = new AtomicInteger(0);
    private final List<UiEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
    private final UiDispatcher dispatcher;

    public HeadlessUiToolkit() {
        this(new HeadlessDispatcher());
    }

    public HeadlessUiToolkit(UiDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public static HeadlessUiToolkit withDedicatedThread() {
        return new HeadlessUiToolkit(HeadlessDispatcher.dedicatedThread());
    }

    @Override public UiDispatcher dispatcher() { return dispatcher; }

    public int pumpEvents() {
        if (dispatcher instanceof HeadlessDispatcher hd) {
            return hd.pumpEvents();
        }
        return 0;
    }

    @Override public void close() {
        if (dispatcher instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

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
        var win = new HeadlessWindow("win-" + idSeq.getAndIncrement(), session, title, width, height);
        session.acquireWindowSlot(win, title);
        openWindows.incrementAndGet();
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
    public UiCheckBox createCheckBox(HostSession session, String text, boolean checked) {
        session.require(HostCapability.UI, "create_checkbox", text);
        dispatcher.assertUiThread("create_checkbox");
        var cb = new HeadlessCheckBox("chk-" + idSeq.getAndIncrement(), session, text, checked);
        session.registerResource(cb::clearListeners);
        return cb;
    }

    @Override
    public UiProgressBar createProgressBar(HostSession session, double value, boolean indeterminate) {
        session.require(HostCapability.UI, "create_progressbar", "");
        dispatcher.assertUiThread("create_progressbar");
        return new HeadlessProgressBar("prg-" + idSeq.getAndIncrement(), value, indeterminate);
    }

    @Override
    public UiListView createListView(HostSession session, List<String> items) {
        session.require(HostCapability.UI, "create_listview", "");
        dispatcher.assertUiThread("create_listview");
        var lv = new HeadlessListView("lst-" + idSeq.getAndIncrement(), session, items);
        session.registerResource(lv::clearListeners);
        return lv;
    }

    @Override
    public UiTimer createTimer(HostSession session, int intervalMs, PyObject callback) {
        session.require(HostCapability.TIMER, "create_timer", intervalMs + "ms");
        if (intervalMs < 1) {
            throw PyErrors.error("ValueError", "timer interval must be >= 1 ms");
        }
        var timer = new HeadlessTimer("tmr-" + idSeq.getAndIncrement(), session, intervalMs, callback);
        session.acquireTimerSlot(timer, intervalMs + "ms");
        return timer;
    }

    private void addListenerChecked(HostSession session, List<PyObject> listeners, PyObject cb, String target) {
        session.require(HostCapability.EVENTS, "attach_listener", target);
        session.checkListenerQuota(listeners.size(), target);
        listeners.add(Objects.requireNonNull(cb, "callback"));
    }

    private void dispatchEvent(HostSession session, List<PyObject> listeners, UiEvent event) {
        eventHistory.add(event);
        PyObject evHandle = event.toPyHandle(session);
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
            session.releaseWindowSlot(this);
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
        private UiLayout layout = UiLayout.FLOW;
        private int spacing = 0;
        private int columns = 2;

        HeadlessPanel(String id) { this.id = id; }

        @Override public String id() { return id; }
        @Override public void add(UiWidget child) {
            dispatcher.assertUiThread("panel.add");
            children.add(Objects.requireNonNull(child, "child"));
        }
        @Override public List<UiWidget> children() { return List.copyOf(children); }
        @Override public UiLayout layout() { return layout; }
        @Override public void setLayout(UiLayout layout) {
            dispatcher.assertUiThread("panel.setLayout");
            this.layout = Objects.requireNonNull(layout, "layout");
        }
        @Override public int spacing() { return spacing; }
        @Override public void setSpacing(int spacing) {
            dispatcher.assertUiThread("panel.setSpacing");
            if (spacing < 0) throw PyErrors.error("ValueError", "spacing must be >= 0");
            this.spacing = spacing;
        }
        @Override public int columns() { return columns; }
        @Override public void setColumns(int columns) {
            dispatcher.assertUiThread("panel.setColumns");
            if (columns < 1) throw PyErrors.error("ValueError", "columns must be >= 1");
            this.columns = columns;
        }
    }

    private final class HeadlessCheckBox implements UiCheckBox {
        private final String id;
        private final HostSession session;
        private String text;
        private boolean checked;
        private final List<PyObject> changeListeners = new CopyOnWriteArrayList<>();

        HeadlessCheckBox(String id, HostSession session, String text, boolean checked) {
            this.id = id;
            this.session = session;
            this.text = text != null ? text : "";
            this.checked = checked;
        }

        @Override public String id() { return id; }
        @Override public String text() { return text; }
        @Override public void setText(String text) {
            dispatcher.assertUiThread("checkbox.setText");
            this.text = text != null ? text : "";
        }
        @Override public boolean isChecked() { return checked; }
        @Override public void setChecked(boolean checked) {
            dispatcher.assertUiThread("checkbox.setChecked");
            if (this.checked != checked) {
                this.checked = checked;
                dispatchEvent(session, changeListeners,
                        new UiEvent("checkbox.change", id, Boolean.toString(checked), System.currentTimeMillis()));
            }
        }
        @Override public void onChange(PyObject callback) {
            session.require(HostCapability.INPUT, "on_change", id);
            addListenerChecked(session, changeListeners, callback, id);
        }
        @Override public int listenerCount() { return changeListeners.size(); }
        void clearListeners() { changeListeners.clear(); }
    }

    private final class HeadlessProgressBar implements UiProgressBar {
        private final String id;
        private double value;
        private boolean indeterminate;

        HeadlessProgressBar(String id, double value, boolean indeterminate) {
            this.id = id;
            this.value = validateProgress(value);
            this.indeterminate = indeterminate;
        }

        @Override public String id() { return id; }
        @Override public double value() { return value; }
        @Override public void setValue(double value) {
            dispatcher.assertUiThread("progressbar.setValue");
            this.value = validateProgress(value);
        }
        @Override public boolean isIndeterminate() { return indeterminate; }
        @Override public void setIndeterminate(boolean indeterminate) {
            dispatcher.assertUiThread("progressbar.setIndeterminate");
            this.indeterminate = indeterminate;
        }
    }

    private final class HeadlessListView implements UiListView {
        private final String id;
        private final HostSession session;
        private List<String> items;
        private int selectedIndex = -1;
        private final List<PyObject> selectListeners = new CopyOnWriteArrayList<>();

        HeadlessListView(String id, HostSession session, List<String> items) {
            this.id = id;
            this.session = session;
            this.items = items == null ? List.of() : List.copyOf(items);
        }

        @Override public String id() { return id; }
        @Override public List<String> items() { return items; }
        @Override public void setItems(List<String> items) {
            dispatcher.assertUiThread("listview.setItems");
            this.items = items == null ? List.of() : List.copyOf(items);
            if (selectedIndex >= this.items.size()) {
                selectedIndex = -1;
            }
        }
        @Override public int selectedIndex() { return selectedIndex; }
        @Override public void setSelectedIndex(int index) {
            dispatcher.assertUiThread("listview.setSelectedIndex");
            if (index < -1 || index >= items.size()) {
                throw PyErrors.error("IndexError", "list view index out of range: " + index);
            }
            this.selectedIndex = index;
            String val = index >= 0 ? items.get(index) : "";
            dispatchEvent(session, selectListeners,
                    new UiEvent("listview.select", id, val, System.currentTimeMillis()));
        }
        @Override public void onSelect(PyObject callback) {
            session.require(HostCapability.INPUT, "on_select", id);
            addListenerChecked(session, selectListeners, callback, id);
        }
        @Override public int listenerCount() { return selectListeners.size(); }
        void clearListeners() { selectListeners.clear(); }
    }

    private static double validateProgress(double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw PyErrors.error("ValueError", "progress value must be between 0.0 and 1.0");
        }
        return value;
    }

    private final class HeadlessTimer implements UiTimer {
        private final String id;
        private final HostSession session;
        private final int intervalMs;
        private final PyObject callback;
        private ScheduledExecutorService exec;
        private ScheduledFuture<?> future;
        private volatile boolean running = false;
        private volatile boolean disposed = false;
        private final java.util.concurrent.atomic.AtomicBoolean tickInFlight =
                new java.util.concurrent.atomic.AtomicBoolean(false);

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
            future = exec.scheduleWithFixedDelay(() -> {
                if (!running || session.isClosed()) {
                    stop();
                    return;
                }
                if (!tickInFlight.compareAndSet(false, true)) {
                    return; // coalesce: do not pile up ticks if UI thread is busy
                }
                dispatcher.runLater(() -> {
                    try {
                        if (running && !session.isClosed()) {
                            try {
                                session.invokeCallback(callback);
                            } catch (RuntimeException ex) {
                                stop();
                                session.recordCallbackFailure(id, ex);
                            }
                        }
                    } finally {
                        tickInFlight.set(false);
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

        @Override
        public synchronized void close() {
            stop();
            if (!disposed) {
                disposed = true;
                session.releaseTimerSlot(this);
            }
        }
    }
}
