package dev.bannerlands.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds keys that exist in the bundled default config.yml but are missing from the server's config.yml.
 *
 * It works on the raw text, never re-serialises the file: every existing line (values, comments, order, indentation,
 * line endings) is left exactly as it is, and only the missing keys - together with the comment lines directly above
 * them - are inserted at the end of the section they belong to. Pure logic, no Bukkit dependency.
 */
public final class ConfigPatcher {
    private ConfigPatcher() { }

    /** A "key:" line. {@code end} is exclusive and excludes trailing blank / comment lines (they belong to what follows). */
    private record Node(String path, String name, int indent, int start, int end, boolean inlineValue) { }

    private static final Pattern KEY = Pattern.compile("^(\\s*)(\"[^\"]*\"|'[^']*'|[^\\s#\\-][^:#]*?)\\s*:(\\s.*|)$");

    public static List<String> split(String text) { return new ArrayList<>(Arrays.asList(text.split("\r?\n", -1))); }

    private static boolean blankOrComment(String l) { String t = l.trim(); return t.isEmpty() || t.startsWith("#"); }

    private static boolean hasInlineValue(String rest) {
        String r = rest.trim();
        return !(r.isEmpty() || r.startsWith("#"));
    }

    private static List<Node> parse(List<String> lines) {
        record Key(int line, int indent, String name, boolean inline) { }
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (blankOrComment(l) || l.trim().startsWith("-")) continue;   // list items belong to the key above
            Matcher m = KEY.matcher(l);
            if (!m.matches()) continue;
            String name = m.group(2).trim();
            if ((name.startsWith("\"") && name.endsWith("\"")) || (name.startsWith("'") && name.endsWith("'"))) {
                name = name.substring(1, name.length() - 1);
            }
            keys.add(new Key(i, m.group(1).length(), name, hasInlineValue(m.group(3))));
        }
        List<Node> out = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Deque<int[]> st = new ArrayDeque<>();     // [indent, index into paths]
        for (Key k : keys) {
            while (!st.isEmpty() && st.peek()[0] >= k.indent()) st.pop();
            String path = st.isEmpty() ? k.name() : paths.get(st.peek()[1]) + "." + k.name();
            paths.add(path);
            st.push(new int[]{k.indent(), paths.size() - 1});
        }
        for (int i = 0; i < keys.size(); i++) {
            Key k = keys.get(i);
            int end = lines.size();
            for (int j = i + 1; j < keys.size(); j++) {
                if (keys.get(j).indent() <= k.indent()) { end = keys.get(j).line(); break; }
            }
            while (end > k.line() + 1 && blankOrComment(lines.get(end - 1))) end--;
            out.add(new Node(paths.get(i), k.name(), k.indent(), k.line(), end, k.inline()));
        }
        return out;
    }

    private static String parentPath(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String shift(String line, int by) {
        if (line.isBlank() || by == 0) return line;
        if (by > 0) return " ".repeat(by) + line;
        int strip = 0;
        while (strip < -by && strip < line.length() && line.charAt(strip) == ' ') strip++;
        return line.substring(strip);
    }

    /**
     * @param live     lines of the server's config.yml
     * @param defaults lines of the bundled config.yml
     * @param added    receives the path of every key (or whole section) that was inserted
     * @return the patched lines (a new list; {@code live} itself is not modified)
     */
    public static List<String> patch(List<String> live, List<String> defaults, List<String> added) {
        List<Node> liveNodes = parse(live), defNodes = parse(defaults);
        Map<String, Node> liveBy = new HashMap<>(), defBy = new HashMap<>();
        for (Node n : liveNodes) liveBy.putIfAbsent(n.path(), n);
        for (Node n : defNodes) defBy.putIfAbsent(n.path(), n);

        // insertion index -> blocks to insert there (kept in the order of the default file)
        TreeMap<Integer, List<List<String>>> inserts = new TreeMap<>();
        int lastReal = live.size();
        while (lastReal > 0 && live.get(lastReal - 1).isBlank()) lastReal--;

        for (Node d : defNodes) {
            if (liveBy.containsKey(d.path())) continue;
            String parent = parentPath(d.path());
            Node liveParent = parent.isEmpty() ? null : liveBy.get(parent);
            if (!parent.isEmpty() && liveParent == null) continue;      // a missing ancestor gets copied with all its children
            if (liveParent != null && liveParent.inlineValue()) continue; // "parent: value" is not a section, can't add children safely

            // the block: comment lines directly above the key + the key and its children
            int from = d.start();
            while (from > 0 && defaults.get(from - 1).trim().startsWith("#")) from--;
            int target, at;
            if (liveParent == null) {
                target = 0;
                at = lastReal;
            } else {
                Node defParent = defBy.get(parent);
                int sibling = -1;
                for (Node n : liveNodes) if (parentPath(n.path()).equals(parent)) { sibling = n.indent(); break; }
                target = sibling >= 0 ? sibling : liveParent.indent() + (d.indent() - (defParent == null ? 0 : defParent.indent()));
                at = liveParent.end();
            }
            int by = target - d.indent();
            List<String> block = new ArrayList<>();
            for (int i = from; i < d.end(); i++) block.add(shift(defaults.get(i), by));
            inserts.computeIfAbsent(at, k -> new ArrayList<>()).add(block);
            added.add(d.path());
        }

        List<String> out = new ArrayList<>(live);
        for (Map.Entry<Integer, List<List<String>>> e : inserts.descendingMap().entrySet()) {
            List<String> add = new ArrayList<>();
            for (List<String> block : e.getValue()) {
                if (e.getKey() == lastReal && !block.isEmpty() && !isNested(block)) add.add("");   // blank line before a new top-level section
                add.addAll(block);
            }
            out.addAll(e.getKey(), add);
        }
        return out;
    }

    /** True if the block's first key line is indented (i.e. it is inserted inside a section). */
    private static boolean isNested(List<String> block) {
        for (String l : block) {
            if (blankOrComment(l)) continue;
            return l.startsWith(" ");
        }
        return false;
    }
}
