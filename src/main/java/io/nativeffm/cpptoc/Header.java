package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.Cond;
import io.nativeffm.cpptoc.Ast.Edits;
import io.nativeffm.cpptoc.Ast.Item;
import io.nativeffm.cpptoc.Ast.Scope;
import io.nativeffm.cpptoc.Lexer.K;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.nativeffm.cpptoc.Lexer.EOF;
import static io.nativeffm.cpptoc.Words.GROUP_ATTRS;
import static io.nativeffm.cpptoc.Words.NOT_FUNCTION;

/**
 * One header: its tokens, the parser's position in them, and the pieces (Items) its C view is rendered from.
 * Parser, Preprocessor and TypeParser fill it; CView renders it.
 */
final class Header {

    final Translation tr;
    final Path file, root;
    final String rel, src;
    final List<Tok> toks;
    final int n;
    final List<Item> items = new ArrayList<>();
    final int[] lineStarts;
    boolean needsStdbool;
    boolean guarded;                                          // #pragma once or an include guard
    boolean bridged;                                          // in --headers: bridged to Java
    boolean needed;                                           // converted to C (bridged, or used by one)
    String javaClass;                                         // bridged: the Java class of its functions
    final Set<String> neededFor = new java.util.TreeSet<>();  // the names it is converted for
    final Set<String> neededBy = new java.util.TreeSet<>();   // ... used by these headers
    final List<Path> includes = new ArrayList<>();            // provider headers it includes
    int p;
    final Deque<Scope> scopes = new ArrayDeque<>();
    final Deque<Cond> conds = new ArrayDeque<>();
    int guardIf = -1, guardDefine = -1, guardEndif = -1;

    Header(Translation tr, Path file, Path root) throws IOException {
        this.tr = tr;
        this.file = file;
        this.root = root;
        this.rel = root.relativize(file).toString().replace('\\', '/');
        this.src = Files.readString(file).replace("\r\n", "\n").replace('\r', '\n');
        this.toks = Lexer.lex(src);
        this.n = toks.size();
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < src.length(); i++) if (src.charAt(i) == '\n') starts.add(i + 1);
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    Tok t(int i) {
        return i >= 0 && i < n ? toks.get(i) : EOF;
    }

    int line(int tok) {
        int off = t(tok).start();
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= off) lo = mid; else hi = mid - 1;
        }
        return lo + 1;
    }

    /** The first index >= i that is not a comment or directive. */
    int sig(int i) {
        while (i < n && (toks.get(i).kind() == K.COMMENT || toks.get(i).kind() == K.DIRECTIVE)) i++;
        return i;
    }

    int prevSig(int i) {
        i--;
        while (i >= 0 && (toks.get(i).kind() == K.COMMENT || toks.get(i).kind() == K.DIRECTIVE)) i--;
        return i;
    }

    /** i at ( [ or {: the index after its closing bracket. */
    int skipBalanced(int i) {
        int depth = 0;
        for (; i < n; i++) {
            Tok x = toks.get(i);
            if (x.kind() != K.PUNCT) continue;
            switch (x.text()) {
                case "(", "[", "{" -> depth++;
                case ")", "]", "}" -> {
                    if (--depth == 0) return i + 1;
                }
                default -> { }
            }
        }
        throw new Fail("unbalanced brackets");
    }

    int skipAngles(int i) {
        int depth = 0;
        for (; i < n; i++) {
            Tok x = toks.get(i);
            if (x.is("(") || x.is("[") || x.is("{")) {
                i = skipBalanced(i) - 1;
            } else if (x.is("<")) {
                depth++;
            } else if (x.is(">")) {
                if (--depth == 0) return i + 1;
            } else if (x.is(">=")) {
                if (--depth == 0) return i + 1;
            }
        }
        throw new Fail("unbalanced <>");
    }

    /** Skips [[...]] and __attribute__((...))-style groups starting at i. */
    int skipAttributes(int i) {
        i = sig(i);
        while (true) {
            if (t(i).is("[") && t(sig(i + 1)).is("[")) {
                i = sig(skipBalanced(i));
            } else if (t(i).ident() && GROUP_ATTRS.contains(t(i).text()) && t(sig(i + 1)).is("(")) {
                i = sig(skipBalanced(sig(i + 1)));
            } else {
                return i;
            }
        }
    }

    /** The last comment on the same line right after token i, or i. */
    int trailingComments(int i) {
        while (i + 1 < n && toks.get(i + 1).kind() == K.COMMENT
                && src.substring(toks.get(i).end(), toks.get(i + 1).start()).indexOf('\n') < 0) i++;
        return i;
    }

    Item add(int first, int last, Function<Item, String> render) {
        Item it = new Item(first, trailingComments(last));
        it.render = render;
        items.add(it);
        p = it.last + 1;
        return it;
    }

    Item drop(int first, int last) {
        return add(first, last, it -> null);
    }

    Item keep(int first, int last) {
        return add(first, last, it -> render(it.first, it.last, new Edits()));
    }

    Item replaced(int first, int last, String text) {
        return add(first, last, it -> {
            Edits e = new Edits();
            e.replace.put(first, text);
            e.drop(first + 1, last);
            return render(it.first, it.last, e);
        });
    }

    List<String> ns() {
        List<String> r = new ArrayList<>();
        for (Iterator<Scope> it = scopes.descendingIterator(); it.hasNext(); ) {
            Scope s = it.next();
            if (s.kind().equals("ns")) r.addAll(s.names());
        }
        return r;
    }

    boolean cLinkageScope() {
        for (Scope s : scopes) {
            if (s.kind().equals("C")) return true;
            if (s.kind().equals("C++")) return false;
        }
        return false;
    }

    /** The C preprocessor conditions the current position is under (for wrapping wrapper declarations). */
    List<String> currentConds() {
        List<String> r = new ArrayList<>();
        for (Iterator<Cond> it = conds.descendingIterator(); it.hasNext(); ) {
            Cond c = it.next();
            if (c.cxx) continue;
            StringBuilder b = new StringBuilder();
            for (String prev : c.previous) b.append(b.isEmpty() ? "" : " && ").append("!(").append(prev).append(")");
            if (c.current != null) b.append(b.isEmpty() ? "" : " && ").append("(").append(c.current).append(")");
            if (!b.isEmpty()) r.add(b.toString());
        }
        return r;
    }

    // ---------------------------------------------------------- rendering helpers

    String render(int from, int to, Edits e) {
        StringBuilder b = new StringBuilder();
        int prevEnd = -1;
        String runWs = null;
        for (int i = from; i <= to && i < n; i++) {
            Tok x = toks.get(i);
            String ws = prevEnd < 0 ? "" : src.substring(prevEnd, x.start());
            prevEnd = x.end();
            if (e.drop.get(i)) {
                if (runWs == null) runWs = ws;
                continue;
            }
            if (runWs != null) {                // whitespace around removed tokens
                if (b.isEmpty()) ws = "";
                else if (ws.indexOf('\n') >= 0) ws = ws;
                else if (runWs.indexOf('\n') >= 0) ws = runWs;
                else ws = runWs.length() <= ws.length() ? runWs : ws;
                runWs = null;
            }
            String text = e.before.getOrDefault(i, "") + e.replace.getOrDefault(i, x.text()) + e.after.getOrDefault(i, "");
            if (ws.isEmpty() && !b.isEmpty() && !text.isEmpty() && word(b.charAt(b.length() - 1)) && word(text.charAt(0))) {
                ws = " ";
            }
            b.append(ws).append(text);
        }
        return b.toString();
    }

    /** The tokens of [from, to] after edits, without comments, as strings (for the safe wrapper files). */
    List<String> words(int from, int to, Edits e) {
        List<String> r = new ArrayList<>();
        for (int i = from; i <= to && i < n; i++) {
            Tok x = toks.get(i);
            if (x.kind() == K.COMMENT || e.drop.get(i)) continue;
            addWords(r, e.before.get(i));
            addWords(r, e.replace.getOrDefault(i, x.text()));
            addWords(r, e.after.get(i));
        }
        return r;
    }

    String summary(int from, int to) {
        Edits e = new Edits();
        for (int i = from; i <= to; i++) if (t(i).kind() == K.COMMENT) e.drop(i, i);
        String s = render(from, to, e).replaceAll("\\s+", " ").strip();
        int body = s.indexOf('{');
        if (body > 0) s = s.substring(0, body).strip() + " {...}";
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private Set<String> identifierCache;

    /** The names the header uses: identifiers, and those in #if and #define lines (not in #include). */
    Set<String> identifiers() {
        if (identifierCache != null) return identifierCache;
        Set<String> r = new HashSet<>();
        Pattern word = Pattern.compile("[A-Za-z_]\\w*");
        for (int i = 0; i < n; i++) {
            Tok x = toks.get(i);
            if (x.ident()) {
                r.add(x.text());
            } else if (x.kind() == K.DIRECTIVE && i != guardIf && i != guardDefine) {
                String kw = keyword(i);
                if (kw.startsWith("include") || kw.equals("pragma")) continue;
                Matcher m = word.matcher(directiveText(i).replaceFirst("^#\\s*\\w+\\s*", ""));
                boolean first = kw.equals("define");                 // skip the name being defined
                while (m.find()) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    r.add(m.group());
                }
            }
        }
        identifierCache = r;
        return r;
    }

    /** {last token of the declaration (';' or the body's '}'), index of the body's '{' or -1}. */
    int[] scanDeclEnd(int i) {
        boolean fn = false, eq = false;
        int prev = -1;
        while (i < n) {
            Tok x = toks.get(i);
            if (x.kind() == K.COMMENT || x.kind() == K.DIRECTIVE) {
                i++;
                continue;
            }
            if (x.is("(")) {
                if (prev >= 0 && toks.get(prev).ident() && !NOT_FUNCTION.contains(toks.get(prev).text())) fn = true;
                i = skipBalanced(i);
                prev = i - 1;
                continue;
            }
            if (x.is("[")) {
                i = skipBalanced(i);
                prev = i - 1;
                continue;
            }
            if (x.is("{")) {
                int after = skipBalanced(i);
                if (fn && !eq) return new int[]{after - 1, i};
                i = after;
                prev = after - 1;
                continue;
            }
            if (x.is("=")) eq = true;
            if (x.is(";")) return new int[]{i, -1};
            if (x.is("}")) return new int[]{Math.max(prev, 0), -1};
            prev = i;
            i++;
        }
        return new int[]{n - 1, -1};
    }

    int sig0(int i) {
        while (i < n && toks.get(i).kind() == K.COMMENT) i++;
        return i;
    }

    String directiveText(int i) {
        return stripComments(toks.get(i).text()).replace("\\\n", " ").replaceAll("\\s+", " ").strip();
    }

    String keyword(int i) {
        Matcher m = Pattern.compile("#\\s*(\\w*)").matcher(toks.get(i).text());
        return m.lookingAt() ? m.group(1) : "";
    }

    /** Parses the header (see Parser): fills items, and the definitions in the Translation. */
    void parse() {
        new Parser(this).parse();
    }

    // ---------------------------------------------------------- token text helpers

    static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static void addWords(List<String> r, String s) {
        if (s == null || s.isBlank()) return;
        for (String w : s.strip().split("\\s+")) r.add(w);
    }

    /** Joins type tokens: "const Camera *camera", "devreg::Grade *result", "void (*cb)(int)". */
    static String joinWords(List<String> ws) {
        StringBuilder b = new StringBuilder();
        String prev = null;
        for (String w : ws) {
            if (prev != null) {
                boolean space;
                if (prev.equals("::") || w.equals("::") || prev.equals("(") || prev.equals("[") || w.equals(")")
                        || w.equals("]") || w.equals(",") || w.equals("[")) space = false;
                else if (prev.equals("*") || prev.equals("&")) space = false;
                else if (w.equals("(")) space = !prev.equals(")");
                else space = true;
                if (prev.equals(",")) space = true;
                if (space) b.append(' ');
            }
            b.append(w);
            prev = w;
        }
        return b.toString();
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
