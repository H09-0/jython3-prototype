# Contributing

1. Fork the repository and create a branch from `master`.
2. Keep the existing behavior green: `./gradlew test`.
3. Add a regression test for any new language or bridge behavior.
4. Put new code in the package that already owns that job (`runtime`, `compile`, `bridge`, `stdlib`, `parse`, or `cli`).
5. Open a pull request that says what changed and how you tested it.
6. Write code, comments, and documentation in English.

Opcode numbers in `org.example.jython3.compile.OpCode` are part of the bytecode
encoding. Add a new code at the end. Do not renumber an existing one.
