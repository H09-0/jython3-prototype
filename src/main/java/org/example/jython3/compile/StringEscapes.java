package org.example.jython3.compile;

/**
 * Python 3 string literal escape processor supporting newline/tab/carriage-return,
 * bell/backspace/formfeed/vertical-tab, hex, 16-bit and 32-bit Unicode escapes,
 * octal escapes, and backslash-newline line continuations.
 */
public final class StringEscapes {
    private StringEscapes() {}

    public static String unescape(String s) {
        if (!s.contains("\\")) return s;
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '\n' -> {} // backslash-newline line continuation
                    case '\r' -> {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                    }
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'v' -> sb.append('\u000B');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case 'x' -> {
                        if (i + 2 < s.length()) {
                            int v = parseHex(s, i + 1, 2);
                            if (v >= 0) {
                                sb.append((char) v);
                                i += 2;
                                break;
                            }
                        }
                        throw new CompileError("(unicode error) 'unicodeescape' codec can't decode \\x escape");
                    }
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            int v = parseHex(s, i + 1, 4);
                            if (v >= 0) {
                                sb.append((char) v);
                                i += 4;
                                break;
                            }
                        }
                        throw new CompileError("(unicode error) 'unicodeescape' codec can't decode \\u escape");
                    }
                    case 'U' -> {
                        if (i + 8 < s.length()) {
                            int v = parseHex(s, i + 1, 8);
                            if (v >= 0 && v <= 0x10FFFF) {
                                sb.appendCodePoint(v);
                                i += 8;
                                break;
                            }
                        }
                        throw new CompileError("(unicode error) 'unicodeescape' codec can't decode \\U escape");
                    }
                    default -> {
                        if (next >= '0' && next <= '7') {
                            int val = next - '0';
                            int count = 1;
                            while (count < 3 && i + 1 < s.length()) {
                                char oc = s.charAt(i + 1);
                                if (oc < '0' || oc > '7') break;
                                val = (val << 3) | (oc - '0');
                                i++;
                                count++;
                            }
                            sb.append((char) (val & 0xFF));
                        } else {
                            sb.append('\\').append(next);
                        }
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int parseHex(String s, int start, int digits) {
        int val = 0;
        for (int k = 0; k < digits; k++) {
            int d = Character.digit(s.charAt(start + k), 16);
            if (d < 0) return -1;
            val = (val << 4) | d;
        }
        return val;
    }
}
