package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.*;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.nativeffm.cpptoc.Words.CALLCONV;

/**
 * One CppToC run: the options, the headers read, and everything they define (macros, structs, enums, typedefs,
 * constants, functions), with the lookups every stage uses. The stages are fields: resolver, cview, safe, ...
 */
final class Translation {

    // ---------------------------------------------------------- options

    /** --include-dir: where headers are found. */
    final List<Path> includeDirs = new ArrayList<>();
    /** --headers: {header or glob, relative to an include dir; Java class or null}. Empty: every header. */
    final List<String[]> headerSpecs = new ArrayList<>();
    /** --exclude (without --headers): headers not to bridge, globs. */
    final List<String> excludes = new ArrayList<>();
    Path out;
    String baseName;
    String prefix = "safe_";
    /** --array-params: pointer parameters that are arrays, [function.]param; the safe wrapper rejects NULL for them. */
    final Set<String> arrayParams = new HashSet<>();
    /** ... and their sizes as given ("" if none): a number or a #define. */
    final Map<String, String> arraySizes = new HashMap<>();
    /**
     * --exceptions: the provider's own exception types, {C++ type, member that gives the message or null}. The safe
     * wrapper catches them (before std::exception) and reports their message; listed derived classes first.
     */
    final List<String[]> exceptionTypes = new ArrayList<>();
    /** --exception-headers: headers that declare those types, if the bridged headers do not include them. */
    final List<String> exceptionHeaders = new ArrayList<>();

    // ---------------------------------------------------------- the stages

    final Resolver resolver = new Resolver(this);
    final CView cview = new CView(this);
    final SafeWrapper safe = new SafeWrapper(this);
    final Selection selection = new Selection(this);

    // ---------------------------------------------------------- what the headers define

    final Map<Path, Header> units = new LinkedHashMap<>();
    /** Headers that another header includes: the safe wrapper files include only the others (top-level headers). */
    final Set<Path> included = new HashSet<>();
    /** Header -> the headers that include it. */
    final Map<Path, Set<String>> includedBy = new HashMap<>();
    /** Headers --exclude left out of a directory (relative paths). */
    final Set<Path> excluded = new LinkedHashSet<>();
    final Deque<Path[]> pending = new ArrayDeque<>();
    final Map<String, List<Macro>> macros = new HashMap<>();
    /** Macro name -> the headers that #define it. */
    final Map<String, Set<Header>> macroUnits = new HashMap<>();
    final Map<String, StructDef> structs = new LinkedHashMap<>();
    final Set<String> forwardStructs = new HashSet<>();
    final Map<String, EnumDef> enums = new LinkedHashMap<>();
    final List<EnumDef> allEnums = new ArrayList<>();
    final Map<String, TypedefDef> typedefs = new LinkedHashMap<>();
    final List<ConstDef> consts = new ArrayList<>();
    final Map<String, Sym> syms = new HashMap<>();
    final List<Fn> functions = new ArrayList<>();
    /** Warnings, with the header they are about (null: general); printed only for headers that are converted. */
    final List<Object[]> warnings = new ArrayList<>();

    // ---------------------------------------------------------- lookups

    /** The include dir a header is in (its own folder if none). */
    Path rootOf(Path file) {
        Path best = null;
        for (Path d : includeDirs) {
            if (file.startsWith(d) && (best == null || d.getNameCount() > best.getNameCount())) best = d;
        }
        return best != null ? best : file.getParent();
    }

    /** #include "inc" from a header in fromDir (null for #include <inc>): {file, include dir}, or null. */
    Path[] findInclude(String inc, Path fromDir) {
        if (fromDir != null) {
            Path f = fromDir.resolve(inc).normalize();
            if (Files.isRegularFile(f)) return new Path[]{f, rootOf(f)};
        }
        for (Path d : includeDirs) {
            Path f = d.resolve(inc).normalize();
            if (Files.isRegularFile(f)) return new Path[]{f, d};
        }
        return null;
    }

    Macro macro(String name) {
        List<Macro> defs = macros.get(name);
        if (defs == null) return null;
        for (Macro m : defs) if (m.kind() != MacroKind.OTHER) return m;      // a DECOR definition wins (DEVICE_API)
        return defs.get(0);
    }

    boolean isDecor(Tok x) {
        if (!x.ident()) return false;
        if (CALLCONV.contains(x.text())) return true;
        Macro m = macro(x.text());
        return m != null && m.kind() == MacroKind.DECOR;
    }

    boolean isFnMacro(Tok x) {
        Macro m = x.ident() ? macro(x.text()) : null;
        return m != null && m.fnLike();
    }

    /** A warning about a header (printed only if the header is converted to C). */
    void warn(Header u, int tok, String message) {
        warnings.add(new Object[]{u, u.rel + ":" + u.line(tok) + ": " + message});
    }

    /** The value of a constant expression, or null (see ConstEval). */
    Long evaluate(List<Tok> tokens, Map<String, Sym> local) {
        return new ConstEval(tokens, syms, local).parse();
    }

    // ---------------------------------------------------------- names

    static String stem(String fileName) {
        return fileName.replaceFirst("\\.[^.]+$", "");
    }

    static String cHeaderName(String rel) {
        return rel.replaceFirst("\\.(hpp|hh|hxx|h\\+\\+|H|h)$", "") + ".h";
    }

    /** "a::b::X" -> a::b::X, b::X, X. */
    static List<String> suffixes(String qualified) {
        List<String> r = new ArrayList<>();
        String[] parts = qualified.split("::");
        for (int i = 0; i < parts.length; i++) r.add(String.join("::", List.of(parts).subList(i, parts.length)));
        return r;
    }

    /** "ns::Grade::PREMIUM" -> ns::Grade::PREMIUM, Grade::PREMIUM (not the bare name). */
    static List<String> qualifiedKeys(String qualified) {
        List<String> r = suffixes(qualified);
        return r.subList(0, Math.max(0, r.size() - 1));
    }
}
