package branch;

import java.util.*;

/** Minimal JSON reader/writer (no external dependencies). */
public final class Json {
    private Json() {}

    public static String write(Object o) { StringBuilder sb = new StringBuilder(); w(o, sb); return sb.toString(); }

    private static void w(Object o, StringBuilder sb) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(s, sb);
        else if (o instanceof Double d && (d.isNaN() || d.isInfinite())) sb.append("null");
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(','); first = false;
                str(String.valueOf(e.getKey()), sb); sb.append(':'); w(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('['); boolean first = true;
            for (Object x : it) { if (!first) sb.append(','); first = false; w(x, sb); }
            sb.append(']');
        } else str(o.toString(), sb);
    }

    private static void str(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20 || c == '<') sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    public static Object parse(String s) {
        P p = new P(s); p.ws(); Object v = p.value(); p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("Unexpected trailing data");
        return v;
    }

    private static final class P {
        final String s; int i = 0;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON"); return s.charAt(i); }
        void expect(char c) { if (peek() != c) throw new IllegalArgumentException("Expected '" + c + "'"); i++; }

        Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>(); expect('{'); ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws(); expect(':'); ws(); m.put(k, value()); ws();
                if (peek() == ',') { i++; continue; }
                expect('}'); return m;
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>(); expect('['); ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                if (peek() == ',') { i++; continue; }
                expect(']'); return l;
            }
        }
        String string() {
            expect('"'); StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek(); i++;
                    switch (e) {
                        case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }
        Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Invalid JSON");
            String t = s.substring(st, i);
            if (t.matches("-?\\d{1,17}")) return Long.parseLong(t);
            return Double.parseDouble(t);
        }
    }
}
