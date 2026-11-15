package org.example.jython3.ui;

import org.example.jython3.runtime.PyErrors;

/**
 * Thread-affinity and dispatch contract for UI toolkits.
 * Prevents off-thread UI mutations and ensures event callbacks execute safely.
 */
public interface UiDispatcher {

    boolean isUiThread();

    void runLater(Runnable task);

    void runAndWait(Runnable task);

    default void assertUiThread(String action) {
        if (!isUiThread()) {
            throw PyErrors.error("RuntimeError",
                    "UI operation '" + action + "' must run on the UI thread (current="
                            + Thread.currentThread().getName() + ")");
        }
    }
}
