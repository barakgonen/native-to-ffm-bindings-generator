package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Which headers: the bridged ones (--headers, or every header of the include dirs but --exclude), the headers they
 * include (read too), which of those must be converted to C (they define something a converted header uses), and
 * the order the safe wrapper files include the bridged headers in.
 */
final class Selection {

    private final Translation tr;
    /** --headers given with "=JavaClass". */
    final Map<Path, String> explicitClass = new HashMap<>();
    private List<Header> rootOrder;

    Selection(Translation tr) {
        this.tr = tr;
    }

    /** Finds the bridged headers and parses them and every provider header they include. */
    void read() throws IOException {
        List<Path> dirs = new ArrayList<>();
        for (Path d : tr.includeDirs) {
            if (!Files.isDirectory(d)) throw new Fail("--include-dir: not a directory: " + d);
            Path n = d.toAbsolutePath().normalize();
            if (!dirs.contains(n)) dirs.add(n);
        }
        tr.includeDirs.clear();
        tr.includeDirs.addAll(dirs);

        // the headers to bridge
        Map<Path, Path> bridgedRoot = new LinkedHashMap<>();
        if (tr.headerSpecs.isEmpty()) {
            List<PathMatcher> byName = new ArrayList<>(), byPath = new ArrayList<>();
            for (String g : tr.excludes) {
                (g.contains("/") ? byPath : byName).add(FileSystems.getDefault().getPathMatcher("glob:" + g));
            }
            for (Path dir : tr.includeDirs) {
                for (Path f : headersIn(dir)) {
                    Path rel = dir.relativize(f);
                    if (byName.stream().anyMatch(m -> m.matches(rel.getFileName()))
                            || byPath.stream().anyMatch(m -> m.matches(rel))) {
                        tr.excluded.add(f);
                    } else {
                        bridgedRoot.putIfAbsent(f, dir);
                    }
                }
            }
            if (bridgedRoot.isEmpty()) throw new Fail("no headers in " + tr.includeDirs);
        } else {
            for (String[] spec : tr.headerSpecs) {
                List<Path[]> found = findBridged(spec[0]);
                if (found.isEmpty()) {
                    List<String> there = new ArrayList<>();
                    for (Path dir : tr.includeDirs) for (Path f : headersIn(dir)) there.add(dir.relativize(f).toString());
                    throw new Fail("--headers: " + spec[0] + " is not in " + tr.includeDirs + " (headers there: "
                            + (there.size() > 30 ? there.subList(0, 30) + "..." : there) + ")");
                }
                for (Path[] f : found) {
                    bridgedRoot.putIfAbsent(f[0], f[1]);
                    if (spec[1] != null) explicitClass.put(f[0], spec[1]);
                }
            }
        }
        for (var e : bridgedRoot.entrySet()) tr.pending.add(new Path[]{e.getKey(), e.getValue()});
        while (!tr.pending.isEmpty()) {
            Path[] next = tr.pending.poll();
            Path key = next[0].toAbsolutePath().normalize();
            if (tr.units.containsKey(key)) continue;
            Header u = new Header(tr, key, next[1].toAbsolutePath().normalize());
            u.bridged = bridgedRoot.containsKey(key);
            tr.units.put(key, u);
            u.parse();
        }
        for (Header u : tr.units.values()) {
            Set<String> by = tr.includedBy.getOrDefault(u.file, Set.of());
            boolean definesTypes = tr.structs.values().stream().anyMatch(x -> x.u == u)
                    || tr.allEnums.stream().anyMatch(x -> x.u == u) || tr.consts.stream().anyMatch(x -> x.u == u);
            if (!u.guarded && by.size() > 1 && definesTypes) {          // #defines and declarations may repeat
                tr.warn(u, 0, u.rel + " has no include guard (#pragma once or #ifndef) and is included by " + by
                        + ": the C++ compiler sees its definitions twice in one translation unit");
            }
        }
    }

    static List<Path> headersIn(Path dir) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile).filter(f -> f.toString().matches(".*\\.(hpp|hh|hxx|h\\+\\+|h)$"))
                    .map(f -> f.toAbsolutePath().normalize()).sorted().toList();
        }
    }

    /** A --headers entry: {file, its include dir} for a path, a glob, or an absolute path. */
    private List<Path[]> findBridged(String spec) throws IOException {
        List<Path[]> r = new ArrayList<>();
        Path abs = Path.of(spec);
        if (abs.isAbsolute()) {
            if (Files.isRegularFile(abs)) r.add(new Path[]{abs.normalize(), tr.rootOf(abs.normalize())});
            return r;
        }
        boolean glob = spec.matches(".*[*?\\[{].*");
        java.nio.file.PathMatcher m = glob ? FileSystems.getDefault().getPathMatcher("glob:" + spec) : null;
        for (Path dir : tr.includeDirs) {
            if (!glob) {
                Path f = dir.resolve(spec).normalize();
                if (Files.isRegularFile(f)) {
                    r.add(new Path[]{f, dir});
                    return r;
                }
                continue;
            }
            for (Path f : headersIn(dir)) {
                Path rel = dir.relativize(f);
                if (m.matches(rel) || !spec.contains("/") && m.matches(rel.getFileName())) r.add(new Path[]{f, dir});
            }
        }
        return r;
    }

    /**
     * Marks the headers to convert to C: the bridged ones, and every header that defines a name (type, typedef,
     * constant, enum constant, macro) used by a header that is converted.
     */
    void markNeeded() {
        Map<String, Set<Header>> definedIn = new HashMap<>();
        java.util.function.BiConsumer<String, Header> def = (name, u) -> {
            if (name != null) definedIn.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(u);
        };
        for (StructDef x : tr.structs.values()) {
            def.accept(x.cName, x.u);
            def.accept(x.tag, x.u);
        }
        for (EnumDef x : tr.allEnums) {
            def.accept(x.cName, x.u);
            def.accept(x.tag, x.u);
            for (var c : x.cNames.entrySet()) {
                def.accept(c.getKey(), x.u);
                def.accept(c.getValue(), x.u);
            }
        }
        for (var e : tr.typedefs.entrySet()) def.accept(e.getKey(), e.getValue().u);
        for (ConstDef c : tr.consts) {
            def.accept(c.cName, c.u);
            def.accept(c.bare, c.u);
        }
        for (var e : tr.macroUnits.entrySet()) for (Header u : e.getValue()) def.accept(e.getKey(), u);
        Deque<Header> todo = new ArrayDeque<>();
        for (Header u : tr.units.values()) {
            if (u.bridged) {
                u.needed = true;
                todo.add(u);
            }
        }
        while (!todo.isEmpty()) {
            Header u = todo.poll();
            for (String name : u.identifiers()) {
                for (Header d : definedIn.getOrDefault(name, Set.of())) {
                    if (d == u) continue;
                    if (!d.bridged) {
                        d.neededFor.add(name);
                        d.neededBy.add(u.rel);
                    }
                    if (!d.needed) {
                        d.needed = true;
                        todo.add(d);
                    }
                }
            }
        }
    }

    /** sensor_types.h -> SensorTypes, sensor.h -> SensorApi (headers with tr.functions get "Api"), 3d.h -> H3d. */
    static String defaultJavaClass(Header u, boolean hasFunctions) {
        StringBuilder b = new StringBuilder();
        for (String part : Translation.stem(u.rel.replaceFirst(".*/", "")).split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String name = b.isEmpty() ? "Header" : b.toString();
        if (Character.isDigit(name.charAt(0))) name = "H" + name;
        if (hasFunctions && !name.toLowerCase().endsWith("api")) name += "Api";
        return name;
    }

    static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** What a header contributes: "-> SensorApi: 2 functions (C++ linkage)", "for real_T, ... (sensor_types.h)". */
    String contents(Header u) {
        if (!u.needed) return "not needed: the bridged headers use nothing it defines";
        List<String> parts = new ArrayList<>();
        long st = tr.structs.values().stream().filter(x -> x.u == u).count();
        long en = tr.enums.values().stream().filter(x -> x.u == u).count();
        long td = new HashSet<>(tr.typedefs.values()).stream().filter(x -> x.u == u).count();
        long co = tr.consts.stream().filter(x -> x.u == u).count();
        long mc = tr.macroUnits.values().stream().filter(x -> x.contains(u)).count();
        List<Fn> fns = tr.functions.stream().filter(f -> f.u == u).toList();
        if (st > 0) parts.add(plural(st, "struct"));
        if (en > 0) parts.add(plural(en, "enum"));
        if (td > 0) parts.add(plural(td, "typedef"));
        if (co + mc > 0) parts.add(plural(co + mc, "constant/macro"));
        if (!fns.isEmpty()) {
            long cxx = fns.stream().filter(f -> !f.cLinkage).count();
            long skipped = fns.stream().filter(f -> f.problem != null).count();
            parts.add(plural(fns.size(), "function") + (cxx > 0 ? " (" + cxx + " with C++ linkage)" : "")
                    + (skipped > 0 ? ", " + skipped + " not translated" : ""));
        }
        String what = parts.isEmpty() ? "nothing to translate" : String.join(", ", parts);
        if (u.bridged) return "-> " + u.javaClass + ": " + what;
        List<String> names = new ArrayList<>(u.neededFor);
        String used = names.size() > 6 ? String.join(", ", names.subList(0, 6)) + ", ..." : String.join(", ", names);
        return "for " + used + " (used by " + String.join(", ", u.neededBy) + "): " + what;
    }

    /**
     * The headers no other header includes (a header without include guard must be included once), in the order
     * the safe wrapper files include them: a header that uses a type of another one without including it comes after it.
     */
    List<Header> roots() {
        if (rootOrder != null) return rootOrder;
        List<Header> bridged = tr.units.values().stream().filter(u -> u.bridged).toList();
        Set<Header> inner = new HashSet<>();                      // bridged headers another bridged header includes
        for (Header b : bridged) for (Header x : closureOf(b)) if (x != b) inner.add(x);
        List<Header> rs = new ArrayList<>(bridged.stream().filter(u -> !inner.contains(u)).toList());
        if (rs.isEmpty()) rs = new ArrayList<>(bridged);
        rs.sort(Comparator.comparing((Header u) -> u.rel));
        Map<String, Header> definedIn = new HashMap<>();
        tr.structs.values().stream().filter(x -> x.u.needed).forEach(x -> definedIn.putIfAbsent(x.cName, x.u));
        tr.enums.values().stream().filter(x -> x.u.needed).forEach(x -> definedIn.putIfAbsent(x.cName, x.u));
        tr.typedefs.forEach((name, x) -> {
            if (x.u.needed) definedIn.putIfAbsent(name, x.u);
        });
        for (String f : tr.forwardStructs) definedIn.remove(f);
        Map<Header, Set<Header>> closure = new HashMap<>();
        for (Header r : rs) closure.put(r, closureOf(r));
        Map<Header, Set<Header>> before = new HashMap<>();
        for (Header r : rs) {
            Set<Header> deps = new LinkedHashSet<>();
            Map<String, String> missing = new TreeMap<>();
            for (Header x : closure.get(r)) {
                for (String name : x.identifiers()) {
                    Header d = definedIn.get(name);
                    if (d == null || closure.get(r).contains(d)) continue;
                    for (Header q : rs) if (q != r && closure.get(q).contains(d)) deps.add(q);
                    missing.put(name, d.rel);
                }
            }
            if (!missing.isEmpty()) {
                tr.warn(r, 0, r.rel + " uses " + missing.keySet() + " from " + new TreeSet<>(missing.values())
                        + " without including it: the safe wrapper files include that header first");
            }
            before.put(r, deps);
        }
        List<Header> order = new ArrayList<>();
        Set<Header> done = new HashSet<>();
        while (order.size() < rs.size()) {
            Header next = rs.stream().filter(r -> !done.contains(r) && done.containsAll(before.get(r))).findFirst().orElse(null);
            if (next == null) {                                    // a cycle: the rest in alphabetical order
                List<Header> rest = rs.stream().filter(r -> !done.contains(r)).toList();
                tr.warn(rest.get(0), 0, "headers use each other's types without including them: "
                        + rest.stream().map(r -> r.rel).toList() + "; included in alphabetical order");
                order.addAll(rest);
                break;
            }
            order.add(next);
            done.add(next);
        }
        rootOrder = order;
        return order;
    }

    private Set<Header> closureOf(Header r) {
        Set<Header> seen = new LinkedHashSet<>();
        Deque<Header> todo = new ArrayDeque<>(List.of(r));
        while (!todo.isEmpty()) {
            Header u = todo.poll();
            if (!seen.add(u)) continue;
            for (Path p : u.includes) {
                Header v = tr.units.get(p);
                if (v != null) todo.add(v);
            }
        }
        return seen;
    }
}
