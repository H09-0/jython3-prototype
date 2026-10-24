package org.example.jython3.parse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;

import org.example.jython3.Jython3Parser;
import org.example.jython3.runtime.InterpreterException;

/**
 * TokenSource filter that implements Python 3 indentation rules.
 *
 * Responsibilities:
 *   • Tracks indentation levels and emits synthetic INDENT and DEDENT tokens.
 *   • Detects IndentationError on mismatched unindents.
 *   • Enforces a maximum indentation depth (MAX_INDENT_DEPTH) to prevent ReDoS / stack overflow.
 *   • Ignores newlines inside parentheses, brackets, and braces (implicit line joining).
 *   • Ignores empty and whitespace-only lines.
 *   • Emits all remaining DEDENT tokens at EOF.
 */
public class PythonIndentStream implements TokenSource {

    private static final int MAX_INDENT_DEPTH = 100;

    private final TokenSource lexer;
    private final Deque<Integer> indentStack = new ArrayDeque<>();
    private final Queue<Token> pendingTokens = new ArrayDeque<>();

    private int parenDepth = 0;
    private Token lastToken = null;
    private boolean isFirstToken = true;

    public PythonIndentStream(TokenSource lexer) {
        this.lexer = lexer;
        this.indentStack.push(0);
    }

    @Override
    public Token nextToken() {
        if (!pendingTokens.isEmpty()) {
            Token t = pendingTokens.poll();
            lastToken = t;
            return t;
        }

        while (true) {
            Token t = lexer.nextToken();

            if (isFirstToken) {
                isFirstToken = false;
                if (t.getType() != Jython3Parser.EOF && t.getType() != Jython3Parser.NEWLINE && t.getCharPositionInLine() > 0) {
                    throw new InterpreterException("IndentationError: unexpected indent at line 1");
                }
            }

            // Track parentheses, brackets, and braces for implicit line continuation
            if (t.getType() == Jython3Parser.LPAREN || t.getType() == Jython3Parser.LBRACK || t.getType() == Jython3Parser.LBRACE) {
                parenDepth++;
                lastToken = t;
                return t;
            }
            if (t.getType() == Jython3Parser.RPAREN || t.getType() == Jython3Parser.RBRACK || t.getType() == Jython3Parser.RBRACE) {
                if (parenDepth > 0) parenDepth--;
                lastToken = t;
                return t;
            }

            // Handle Newlines and Indentation
            if (t.getType() == Jython3Parser.NEWLINE) {
                if (parenDepth > 0) {
                    // Implicit line joining inside (), [], {}
                    continue;
                }

                int spaces = calculateIndent(t.getText());

                // Peek ahead for blank lines / comments
                Token next = lexer.nextToken();
                while (next.getType() == Jython3Parser.NEWLINE) {
                    spaces = calculateIndent(next.getText());
                    next = lexer.nextToken();
                }

                if (next.getType() == Jython3Parser.EOF) {
                    if (lastToken != null && lastToken.getType() != Jython3Parser.NEWLINE && lastToken.getType() != Jython3Parser.DEDENT) {
                        pendingTokens.add(createToken(Jython3Parser.NEWLINE, "\n", t));
                    }
                    while (indentStack.size() > 1) {
                        indentStack.pop();
                        pendingTokens.add(createToken(Jython3Parser.DEDENT, "", t));
                    }
                    pendingTokens.add(next);
                    Token res = pendingTokens.poll();
                    lastToken = res;
                    return res;
                }

                int currentIndent = indentStack.peek();
                if (spaces > currentIndent) {
                    if (indentStack.size() >= MAX_INDENT_DEPTH) {
                        throw new InterpreterException(
                                "IndentationError: maximum indentation depth exceeded (limit: " + MAX_INDENT_DEPTH + ")");
                    }
                    pendingTokens.add(createToken(Jython3Parser.NEWLINE, "\n", t));
                    pendingTokens.add(createToken(Jython3Parser.INDENT, "", t));
                    indentStack.push(spaces);
                } else if (spaces < currentIndent) {
                    pendingTokens.add(createToken(Jython3Parser.NEWLINE, "\n", t));
                    while (indentStack.size() > 1 && indentStack.peek() > spaces) {
                        indentStack.pop();
                        pendingTokens.add(createToken(Jython3Parser.DEDENT, "", t));
                    }
                    if (indentStack.peek() != spaces) {
                        throw new InterpreterException(
                                "IndentationError: unindent does not match any outer indentation level");
                    }
                } else {
                    if (lastToken != null && lastToken.getType() != Jython3Parser.NEWLINE && lastToken.getType() != Jython3Parser.DEDENT) {
                        pendingTokens.add(createToken(Jython3Parser.NEWLINE, "\n", t));
                    }
                }

                if (next.getType() == Jython3Parser.LPAREN || next.getType() == Jython3Parser.LBRACK || next.getType() == Jython3Parser.LBRACE) {
                    parenDepth++;
                } else if (next.getType() == Jython3Parser.RPAREN || next.getType() == Jython3Parser.RBRACK || next.getType() == Jython3Parser.RBRACE) {
                    if (parenDepth > 0) parenDepth--;
                }

                pendingTokens.add(next);
                Token res = pendingTokens.poll();
                lastToken = res;
                return res;
            }

            if (t.getType() == Jython3Parser.EOF) {
                if (lastToken != null && lastToken.getType() != Jython3Parser.NEWLINE && lastToken.getType() != Jython3Parser.DEDENT) {
                    pendingTokens.add(createToken(Jython3Parser.NEWLINE, "\n", t));
                }
                while (indentStack.size() > 1) {
                    indentStack.pop();
                    pendingTokens.add(createToken(Jython3Parser.DEDENT, "", t));
                }
                pendingTokens.add(t);
                Token res = pendingTokens.poll();
                lastToken = res;
                return res;
            }

            lastToken = t;
            return t;
        }
    }

    private static int calculateIndent(String text) {
        int spaces = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                spaces++;
            } else if (c == '\t') {
                spaces = (spaces / 4 + 1) * 4;
            }
        }
        return spaces;
    }

    private static Token createToken(int type, String text, Token ref) {
        var ct = new CommonToken(type, text);
        if (ref != null) {
            ct.setLine(ref.getLine());
            ct.setCharPositionInLine(ref.getCharPositionInLine());
        }
        return ct;
    }

    @Override
    public int getLine() {
        return lexer.getLine();
    }

    @Override
    public int getCharPositionInLine() {
        return lexer.getCharPositionInLine();
    }

    @Override
    public CharStream getInputStream() {
        return lexer.getInputStream();
    }

    @Override
    public String getSourceName() {
        return lexer.getSourceName();
    }

    @Override
    public void setTokenFactory(TokenFactory<?> factory) {
        lexer.setTokenFactory(factory);
    }

    @Override
    public TokenFactory<?> getTokenFactory() {
        return lexer.getTokenFactory();
    }
}
