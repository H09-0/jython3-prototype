package org.example.jython3.stdlib.modules;

import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.ui.UiToolkit;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Capability-gated Python {@code ui} module exposing opaque widget handles
 * ({@code Window}, {@code Button}, {@code Label}, {@code TextField}, {@code Panel},
 * {@code Timer}) backed by a {@link UiToolkit}.
 */
public final class UiModule {
    private UiModule() {}

    public static PyModule create(HostSession session, UiToolkit toolkit) {
        Map<PyHostHandle, UiToolkit.UiWidget> widgetRegistry = new IdentityHashMap<>();
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("Window", new PyBuiltinFunction("ui.Window", (args, kwargs) -> {
            String title = args.length >= 1 ? strArg(args[0], "Window title") : "Window";
            int width = kwargs.containsKey("width") ? intArg(kwargs.get("width")) : (args.length >= 2 ? intArg(args[1]) : 640);
            int height = kwargs.containsKey("height") ? intArg(kwargs.get("height")) : (args.length >= 3 ? intArg(args[2]) : 480);
            UiToolkit.UiWindow win = toolkit.createWindow(session, title, width, height);
            return wrapWindow(session, win, widgetRegistry);
        }));

        attrs.put("Button", new PyBuiltinFunction("ui.Button", args -> {
            String text = args.length >= 1 ? strArg(args[0], "Button text") : "";
            return wrapButton(session, toolkit.createButton(session, text), widgetRegistry);
        }));

        attrs.put("Label", new PyBuiltinFunction("ui.Label", args -> {
            String text = args.length >= 1 ? strArg(args[0], "Label text") : "";
            return wrapLabel(session, toolkit.createLabel(session, text), widgetRegistry);
        }));

        attrs.put("TextField", new PyBuiltinFunction("ui.TextField", args -> {
            String text = args.length >= 1 ? strArg(args[0], "TextField text") : "";
            return wrapTextField(session, toolkit.createTextField(session, text), widgetRegistry);
        }));

        attrs.put("Panel", new PyBuiltinFunction("ui.Panel", args -> {
            return wrapPanel(session, toolkit.createPanel(session), widgetRegistry);
        }));

        attrs.put("Timer", new PyBuiltinFunction("ui.Timer", args -> {
            if (args.length != 2) {
                throw PyErrors.error("TypeError", "ui.Timer(interval_ms, callback) takes exactly 2 arguments");
            }
            int ms = intArg(args[0]);
            return wrapTimer(session, toolkit.createTimer(session, ms, args[1]));
        }));

        return new PyModule("ui", attrs);
    }

    private static PyHostHandle wrapWindow(HostSession session, UiToolkit.UiWindow win, Map<PyHostHandle, UiToolkit.UiWidget> reg) {
        var childHandles = new java.util.ArrayList<PyObject>();
        PyHostHandle h = PyHostHandle.builder("ui.Window")
                .bindSession(session)
                .prop("id", () -> new PyStr(win.id()))
                .prop("title", () -> new PyStr(win.title()), v -> win.setTitle(strArg(v, "title")))
                .prop("width", () -> PyInt.of(win.width()))
                .prop("height", () -> PyInt.of(win.height()))
                .prop("visible", () -> PyBool.of(win.isVisible()))
                .prop("closed", () -> PyBool.of(win.isClosed()))
                .method("show", new PyBuiltinFunction("Window.show", a -> { win.show(); return PyNull.INSTANCE; }))
                .method("close", new PyBuiltinFunction("Window.close", a -> { win.close(); return PyNull.INSTANCE; }))
                .method("add", new PyBuiltinFunction("Window.add", a -> {
                    if (a.length != 1 || !(a[0] instanceof PyHostHandle ch) || !reg.containsKey(ch)) {
                        throw PyErrors.error("TypeError", "Window.add() expects a UI widget");
                    }
                    win.add(reg.get(ch));
                    childHandles.add(ch);
                    return PyNull.INSTANCE;
                }))
                .method("children", new PyBuiltinFunction("Window.children", a -> new PyList(new java.util.ArrayList<>(childHandles))))
                .method("on_close", new PyBuiltinFunction("Window.on_close", a -> {
                    if (a.length != 1) throw PyErrors.error("TypeError", "on_close() takes 1 callback");
                    win.onClose(a[0]);
                    return PyNull.INSTANCE;
                }))
                .build();
        reg.put(h, win);
        return h;
    }

    private static PyHostHandle wrapButton(HostSession session, UiToolkit.UiButton btn, Map<PyHostHandle, UiToolkit.UiWidget> reg) {
        PyHostHandle h = PyHostHandle.builder("ui.Button")
                .bindSession(session)
                .prop("id", () -> new PyStr(btn.id()))
                .prop("text", () -> new PyStr(btn.text()), v -> btn.setText(strArg(v, "text")))
                .prop("enabled", () -> PyBool.of(btn.isEnabled()), v -> btn.setEnabled(boolArg(v)))
                .method("on_click", new PyBuiltinFunction("Button.on_click", a -> {
                    if (a.length != 1) throw PyErrors.error("TypeError", "on_click() takes 1 callback");
                    btn.onClick(a[0]);
                    return PyNull.INSTANCE;
                }))
                .method("click", new PyBuiltinFunction("Button.click", a -> {
                    btn.click();
                    return PyNull.INSTANCE;
                }))
                .build();
        reg.put(h, btn);
        return h;
    }

    private static PyHostHandle wrapLabel(HostSession session, UiToolkit.UiLabel lbl, Map<PyHostHandle, UiToolkit.UiWidget> reg) {
        PyHostHandle h = PyHostHandle.builder("ui.Label")
                .bindSession(session)
                .prop("id", () -> new PyStr(lbl.id()))
                .prop("text", () -> new PyStr(lbl.text()), v -> lbl.setText(strArg(v, "text")))
                .build();
        reg.put(h, lbl);
        return h;
    }

    private static PyHostHandle wrapTextField(HostSession session, UiToolkit.UiTextField tf, Map<PyHostHandle, UiToolkit.UiWidget> reg) {
        PyHostHandle h = PyHostHandle.builder("ui.TextField")
                .bindSession(session)
                .prop("id", () -> new PyStr(tf.id()))
                .prop("text", () -> new PyStr(tf.text()), v -> tf.setText(strArg(v, "text")))
                .method("on_change", new PyBuiltinFunction("TextField.on_change", a -> {
                    if (a.length != 1) throw PyErrors.error("TypeError", "on_change() takes 1 callback");
                    tf.onChange(a[0]);
                    return PyNull.INSTANCE;
                }))
                .method("set_text", new PyBuiltinFunction("TextField.set_text", a -> {
                    if (a.length != 1) throw PyErrors.error("TypeError", "set_text() takes 1 argument");
                    tf.setText(strArg(a[0], "text"));
                    return PyNull.INSTANCE;
                }))
                .build();
        reg.put(h, tf);
        return h;
    }

    private static PyHostHandle wrapPanel(HostSession session, UiToolkit.UiPanel pnl, Map<PyHostHandle, UiToolkit.UiWidget> reg) {
        var childHandles = new java.util.ArrayList<PyObject>();
        PyHostHandle h = PyHostHandle.builder("ui.Panel")
                .bindSession(session)
                .prop("id", () -> new PyStr(pnl.id()))
                .method("add", new PyBuiltinFunction("Panel.add", a -> {
                    if (a.length != 1 || !(a[0] instanceof PyHostHandle ch) || !reg.containsKey(ch)) {
                        throw PyErrors.error("TypeError", "Panel.add() expects a UI widget");
                    }
                    pnl.add(reg.get(ch));
                    childHandles.add(ch);
                    return PyNull.INSTANCE;
                }))
                .method("children", new PyBuiltinFunction("Panel.children", a -> new PyList(new java.util.ArrayList<>(childHandles))))
                .build();
        reg.put(h, pnl);
        return h;
    }

    private static PyHostHandle wrapTimer(HostSession session, UiToolkit.UiTimer timer) {
        return PyHostHandle.builder("ui.Timer")
                .bindSession(session)
                .prop("id", () -> new PyStr(timer.id()))
                .prop("interval_ms", () -> PyInt.of(timer.intervalMs()))
                .prop("running", () -> PyBool.of(timer.isRunning()))
                .method("start", new PyBuiltinFunction("Timer.start", a -> { timer.start(); return PyNull.INSTANCE; }))
                .method("stop", new PyBuiltinFunction("Timer.stop", a -> { timer.stop(); return PyNull.INSTANCE; }))
                .build();
    }

    private static String strArg(PyObject o, String label) {
        if (o instanceof PyStr s) return s.value();
        throw PyErrors.error("TypeError", label + " must be str");
    }

    private static int intArg(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        throw PyErrors.error("TypeError", "expected int");
    }

    private static boolean boolArg(PyObject o) {
        if (o instanceof PyBool b) return b.value();
        throw PyErrors.error("TypeError", "expected bool");
    }
}
