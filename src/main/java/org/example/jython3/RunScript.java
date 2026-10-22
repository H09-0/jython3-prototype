package org.example.jython3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Simple script runner - executes a .py file line by line.
 */
public class RunScript {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java RunScript <script.py>");
            return;
        }

        var interpreter = new Jython3Interpreter();
        String filename = args[0];

        try (var br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank() && !line.trim().startsWith("#")) {
                    interpreter.exec(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }
}
