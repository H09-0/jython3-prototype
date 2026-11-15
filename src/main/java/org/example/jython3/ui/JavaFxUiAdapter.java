package org.example.jython3.ui;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Thread-safe JavaFX Application Thread dispatch & UI adapter.
 *
 * <p>A JavaFX host application (such as Oculix) passes {@code Platform::runLater}
 * and {@code Platform::isFxApplicationThread} alongside a delegate {@link UiToolkit}
 * (or JavaFX-backed widget factories). All UI mutations are verified on the FX
 * thread, {@link #runAndWait(Runnable)} enforces a bounded wait timeout, and
 * errors on the FX thread propagate cleanly without hanging the caller.
 */
public final class JavaFxUiAdapter implements UiToolkit {

    public static final class FxDispatcher implements UiDispatcher {
        private final Consumer<Runnable> fxRunLater;
        private final BooleanSupplier isFxThread;
        private final Duration syncTimeout;

        public FxDispatcher(Consumer<Runnable> fxRunLater, BooleanSupplier isFxThread) {
            this(fxRunLater, isFxThread, Duration.ofSeconds(5));
        }

        public FxDispatcher(Consumer<Runnable> fxRunLater, BooleanSupplier isFxThread, Duration syncTimeout) {
            this.fxRunLater = Objects.requireNonNull(fxRunLater, "fxRunLater");
            this.isFxThread = Objects.requireNonNull(isFxThread, "isFxThread");
            this.syncTimeout = Objects.requireNonNull(syncTimeout, "syncTimeout");
        }

        @Override public boolean isUiThread() {
            return isFxThread.getAsBoolean();
        }

        @Override public void runLater(Runnable task) {
            fxRunLater.accept(Objects.requireNonNull(task, "task"));
        }

        @Override public void runAndWait(Runnable task) {
            if (isUiThread()) {
                task.run();
                return;
            }
            var latch = new CountDownLatch(1);
            var failure = new AtomicReference<RuntimeException>();
            fxRunLater.accept(() -> {
                try {
                    task.run();
                } catch (RuntimeException re) {
                    failure.set(re);
                } catch (Error err) {
                    failure.set(PyErrors.fromJava(err));
                } finally {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(syncTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw PyErrors.error("TimeoutError", "timed out waiting for JavaFX Application Thread");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting for JavaFX Application Thread");
            }
            if (failure.get() != null) {
                throw failure.get();
            }
        }
    }

    private final FxDispatcher dispatcher;
    private final UiToolkit delegate;

    public JavaFxUiAdapter(FxDispatcher dispatcher, UiToolkit delegate) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public FxDispatcher dispatcher() { return dispatcher; }

    @Override
    public UiWindow createWindow(HostSession session, String title, int width, int height) {
        session.require(HostCapability.UI, "fx.create_window", title);
        dispatcher.assertUiThread("fx.create_window");
        return delegate.createWindow(session, title, width, height);
    }

    @Override
    public UiButton createButton(HostSession session, String text) {
        session.require(HostCapability.UI, "fx.create_button", text);
        dispatcher.assertUiThread("fx.create_button");
        return delegate.createButton(session, text);
    }

    @Override
    public UiLabel createLabel(HostSession session, String text) {
        session.require(HostCapability.UI, "fx.create_label", text);
        dispatcher.assertUiThread("fx.create_label");
        return delegate.createLabel(session, text);
    }

    @Override
    public UiTextField createTextField(HostSession session, String text) {
        session.require(HostCapability.UI, "fx.create_textfield", text);
        dispatcher.assertUiThread("fx.create_textfield");
        return delegate.createTextField(session, text);
    }

    @Override
    public UiPanel createPanel(HostSession session) {
        session.require(HostCapability.UI, "fx.create_panel", "");
        dispatcher.assertUiThread("fx.create_panel");
        return delegate.createPanel(session);
    }

    @Override
    public UiCheckBox createCheckBox(HostSession session, String text, boolean checked) {
        session.require(HostCapability.UI, "fx.create_checkbox", text);
        dispatcher.assertUiThread("fx.create_checkbox");
        return delegate.createCheckBox(session, text, checked);
    }

    @Override
    public UiProgressBar createProgressBar(HostSession session, double value, boolean indeterminate) {
        session.require(HostCapability.UI, "fx.create_progressbar", "");
        dispatcher.assertUiThread("fx.create_progressbar");
        return delegate.createProgressBar(session, value, indeterminate);
    }

    @Override
    public UiListView createListView(HostSession session, java.util.List<String> items) {
        session.require(HostCapability.UI, "fx.create_listview", "");
        dispatcher.assertUiThread("fx.create_listview");
        return delegate.createListView(session, items);
    }

    @Override
    public UiTimer createTimer(HostSession session, int intervalMs, PyObject callback) {
        session.require(HostCapability.TIMER, "fx.create_timer", intervalMs + "ms");
        return delegate.createTimer(session, intervalMs, callback);
    }
}
