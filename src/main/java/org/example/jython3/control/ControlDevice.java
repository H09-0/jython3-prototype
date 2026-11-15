package org.example.jython3.control;

import org.example.jython3.runtime.PyObject;

import java.util.Map;

/**
 * Abstract host-controlled device (simulated or hardware-bridged).
 * Never exposes raw Java objects or reflection to Python scripts.
 */
public interface ControlDevice {

    String id();

    PyObject execute(String command, Map<String, PyObject> args);

    Map<String, PyObject> snapshotState();
}
