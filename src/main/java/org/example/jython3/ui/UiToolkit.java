package org.example.jython3.ui;

import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyObject;

import java.util.List;

/**
 * Abstract host UI toolkit implemented by headless test toolkits and real
 * Swing/JavaFX host adapters. All resources created by a toolkit are bound to
 * a {@link HostSession} and cleaned up on interpreter reset or close.
 */
public interface UiToolkit {

    enum UiLayout {
        FLOW, VBOX, HBOX, GRID;

        public static UiLayout parse(String raw) {
            if (raw == null) return FLOW;
            return switch (raw.trim().toLowerCase()) {
                case "flow" -> FLOW;
                case "vbox", "vertical" -> VBOX;
                case "hbox", "horizontal" -> HBOX;
                case "grid" -> GRID;
                default -> throw new IllegalArgumentException("unknown layout: " + raw);
            };
        }
    }

    UiDispatcher dispatcher();

    UiWindow createWindow(HostSession session, String title, int width, int height);

    UiButton createButton(HostSession session, String text);

    UiLabel createLabel(HostSession session, String text);

    UiTextField createTextField(HostSession session, String text);

    UiPanel createPanel(HostSession session);

    UiCheckBox createCheckBox(HostSession session, String text, boolean checked);

    UiProgressBar createProgressBar(HostSession session, double value, boolean indeterminate);

    UiListView createListView(HostSession session, List<String> items);

    UiTimer createTimer(HostSession session, int intervalMs, PyObject callback);

    interface UiWidget {
        String id();
    }

    interface UiWindow extends UiWidget, AutoCloseable {
        String title();
        void setTitle(String title);
        int width();
        int height();
        boolean isVisible();
        boolean isClosed();
        void show();
        @Override void close();
        void add(UiWidget child);
        List<UiWidget> children();
        void onClose(PyObject callback);
        int listenerCount();
    }

    interface UiButton extends UiWidget {
        String text();
        void setText(String text);
        boolean isEnabled();
        void setEnabled(boolean enabled);
        void onClick(PyObject callback);
        void click();
        int listenerCount();
    }

    interface UiLabel extends UiWidget {
        String text();
        void setText(String text);
    }

    interface UiTextField extends UiWidget {
        String text();
        void setText(String text);
        void onChange(PyObject callback);
        int listenerCount();
    }

    interface UiPanel extends UiWidget {
        void add(UiWidget child);
        List<UiWidget> children();
        UiLayout layout();
        void setLayout(UiLayout layout);
        int spacing();
        void setSpacing(int spacing);
        int columns();
        void setColumns(int columns);
    }

    interface UiCheckBox extends UiWidget {
        String text();
        void setText(String text);
        boolean isChecked();
        void setChecked(boolean checked);
        void onChange(PyObject callback);
        int listenerCount();
    }

    interface UiProgressBar extends UiWidget {
        double value();
        void setValue(double value);
        boolean isIndeterminate();
        void setIndeterminate(boolean indeterminate);
    }

    interface UiListView extends UiWidget {
        List<String> items();
        void setItems(List<String> items);
        int selectedIndex();
        void setSelectedIndex(int index);
        void onSelect(PyObject callback);
        int listenerCount();
    }

    interface UiTimer extends AutoCloseable {
        String id();
        int intervalMs();
        boolean isRunning();
        void start();
        void stop();
        @Override default void close() { stop(); }
    }
}
