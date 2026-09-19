package com.gadgetman.jarvis.core.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits one value in the text of a YAML document, leaving everything else,
 * comments included, exactly as it was.
 *
 * <p>A full dump through snakeyaml loses every comment in config.yml, and
 * the comments are the documentation. So when a setting is changed from a
 * menu or a command, the line that holds it is rewritten in place instead.
 * Only block-style mappings are understood, which is what config.yml is;
 * a path that cannot be found in the text is reported as such and the
 * caller falls back to a dump.
 */
final class YamlInPlace {

    private YamlInPlace() {
    }

    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([^\\s#\\-][^:]*?):(?:\\s(.*))?$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)-(\\s.*|$)");

    /**
     * The document with the value at the dotted path replaced, or null when
     * the path is not in the text as a block mapping key.
     */
    static String set(String text, String path, Object value) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        boolean crlf = text.contains("\r\n");
        if (crlf) lines.replaceAll(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        String[] target = path.split("\\.");

        Deque<int[]> stack = new ArrayDeque<>();   // indent of each open mapping key
        List<String> keys = new ArrayList<>();      // and its name, in step with the stack
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (LIST_ITEM.matcher(line).matches()) continue;
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) continue;
            int indent = m.group(1).length();
            while (!stack.isEmpty() && stack.peek()[0] >= indent) {
                stack.pop();
                keys.remove(keys.size() - 1);
            }
            stack.push(new int[] { indent });
            keys.add(m.group(2).trim());
            if (!matches(keys, target)) continue;

            String rest = m.group(3) == null ? "" : m.group(3);
            String comment = inlineComment(rest);          // "   # note", gap included
            String oldValue = rest.substring(0, rest.length() - comment.length()).trim();
            String prefix = m.group(1) + m.group(2).trim() + ":";
            if (!comment.isEmpty() && oldValue.isEmpty()) comment = "  " + comment.trim();

            if (value instanceof List<?> list) {
                replaceList(lines, i, indent, prefix, comment, list);
            } else {
                boolean quoted = oldValue.startsWith("\"") || oldValue.startsWith("'");
                lines.set(i, prefix + " " + scalar(value, quoted) + comment);
                // A block that used to follow this key (a list, say) is now stale.
                removeBlockBelow(lines, i, indent);
            }
            return String.join(crlf ? "\r\n" : "\n", lines);
        }
        return null;
    }

    private static boolean matches(List<String> keys, String[] target) {
        if (keys.size() != target.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (!keys.get(i).equals(target[i])) return false;
        }
        return true;
    }

    /** The " # ..." tail of a value, outside quotes, with the whitespace before it; or "". */
    private static String inlineComment(String rest) {
        boolean inDouble = false, inSingle = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '#' && !inDouble && !inSingle && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) {
                int start = i;
                while (start > 0 && Character.isWhitespace(rest.charAt(start - 1))) start--;
                return rest.substring(start);
            }
        }
        return "";
    }

    /** A value as YAML text, quoted when the plain form would read as something else. */
    static String scalar(Object value, boolean preferQuoted) {
        if (value == null) return "\"\"";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        String s = value.toString();
        boolean plainSafe = !s.isEmpty() && !preferQuoted
                && !s.contains("#") && !s.contains(": ") && !s.endsWith(":")
                && !s.startsWith("-") && !s.startsWith("[") && !s.startsWith("{") && !s.startsWith("\"")
                && !s.startsWith("'") && !s.startsWith("*") && !s.startsWith("&") && !s.startsWith("!")
                && !s.startsWith("%") && !s.startsWith("@") && !s.startsWith("`") && !s.startsWith("|")
                && !s.startsWith(">") && !s.startsWith("?") && !s.startsWith(" ") && !s.endsWith(" ")
                && !looksLikeOtherType(s);
        if (plainSafe) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean looksLikeOtherType(String s) {
        String l = s.toLowerCase();
        if (l.equals("true") || l.equals("false") || l.equals("null") || l.equals("~")
                || l.equals("yes") || l.equals("no") || l.equals("on") || l.equals("off")) return true;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return s.matches("0x[0-9a-fA-F]+|0o[0-7]+|[0-9_]+");
        }
    }

    /** Swap the list under a key for a new one, keeping the key's own line and comment. */
    private static void replaceList(List<String> lines, int keyLine, int indent, String prefix, String comment, List<?> items) {
        int itemIndent = indent + 2;
        int end = keyLine + 1;
        boolean sawItem = false;
        while (end < lines.size()) {
            String l = lines.get(end);
            if (l.isBlank() || l.stripLeading().startsWith("#")) {
                // Comments and blanks inside the list belong to it; after it, they stay.
                int next = nextContent(lines, end);
                if (next < 0 || !isItemDeeperThan(lines.get(next), indent)) break;
                end++;
                continue;
            }
            Matcher item = LIST_ITEM.matcher(l);
            if (item.matches() && item.group(1).length() >= indent) {
                if (!sawItem) itemIndent = item.group(1).length();
                sawItem = true;
                end++;
                continue;
            }
            break;
        }
        List<String> block = new ArrayList<>();
        block.add(prefix + comment);
        String pad = " ".repeat(itemIndent);
        for (Object item : items) block.add(pad + "- " + scalar(item, false));
        if (items.isEmpty()) block.set(0, prefix + " []" + comment);
        lines.subList(keyLine, end).clear();
        lines.addAll(keyLine, block);
    }

    private static int nextContent(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!l.isBlank() && !l.stripLeading().startsWith("#")) return i;
        }
        return -1;
    }

    private static boolean isItemDeeperThan(String line, int indent) {
        Matcher item = LIST_ITEM.matcher(line);
        return item.matches() && item.group(1).length() >= indent;
    }

    /** Drop list items or nested keys that sat under a key which is now a scalar. */
    private static void removeBlockBelow(List<String> lines, int keyLine, int indent) {
        int end = keyLine + 1;
        while (end < lines.size()) {
            String l = lines.get(end);
            if (l.isBlank() || l.stripLeading().startsWith("#")) {
                int next = nextContent(lines, end);
                if (next < 0 || leadingSpaces(lines.get(next)) <= indent) break;
                end++;
                continue;
            }
            if (leadingSpaces(l) <= indent && !LIST_ITEM.matcher(l).matches()) break;
            if (LIST_ITEM.matcher(l).matches() && leadingSpaces(l) < indent) break;
            end++;
        }
        if (end > keyLine + 1) lines.subList(keyLine + 1, end).clear();
    }

    private static int leadingSpaces(String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') n++;
        return n;
    }
}
