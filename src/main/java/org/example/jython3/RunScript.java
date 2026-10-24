package org.example.jython3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Script runner – executes a Python script file using Jython3Interpreter.
 */
public class RunScript {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java RunScript <script.py>");
            return;
        }

        var interpreter = new Jython3Interpreter();
        String filename = args[0];

        try {
            String script = Files.readString(Path.of(filename));
            interpreter.exec(script);
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }
}
