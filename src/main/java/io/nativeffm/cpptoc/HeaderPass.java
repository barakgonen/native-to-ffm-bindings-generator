package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.Edits;
import io.nativeffm.cpptoc.Ast.Item;
import io.nativeffm.cpptoc.Ast.Macro;
import io.nativeffm.cpptoc.Ast.MacroKind;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.util.List;
import java.util.function.Function;

/** Base of the parser passes over one header: short names for the header's navigation and item methods. */
abstract class HeaderPass {

    final Header h;
    final Translation tr;

    HeaderPass(Header h) {
        this.h = h;
        this.tr = h.tr;
    }

    // ---- tokens
    Tok t(int i) { return h.t(i); }
    int sig(int i) { return h.sig(i); }
    int sig0(int i) { return h.sig0(i); }
    int prevSig(int i) { return h.prevSig(i); }
    int skipBalanced(int i) { return h.skipBalanced(i); }
    int skipAngles(int i) { return h.skipAngles(i); }
    int skipAttributes(int i) { return h.skipAttributes(i); }
    int trailingComments(int i) { return h.trailingComments(i); }
    int[] scanDeclEnd(int i) { return h.scanDeclEnd(i); }
    String directiveText(int i) { return h.directiveText(i); }
    String keyword(int i) { return h.keyword(i); }

    // ---- output pieces of the C view
    Item add(int first, int last, Function<Item, String> render) { return h.add(first, last, render); }
    Item drop(int first, int last) { return h.drop(first, last); }
    Item keep(int first, int last) { return h.keep(first, last); }
    Item replaced(int first, int last, String text) { return h.replaced(first, last, text); }
    String render(int from, int to, Edits e) { return h.render(from, to, e); }
    String summary(int from, int to) { return h.summary(from, to); }

    // ---- where the parser is
    List<String> ns() { return h.ns(); }
    boolean cLinkageScope() { return h.cLinkageScope(); }
    List<String> currentConds() { return h.currentConds(); }

    // ---- macros and warnings
    Macro macro(String name) { return tr.macro(name); }
    boolean isDecor(Tok x) { return tr.isDecor(x); }
    boolean isFnMacro(Tok x) { return tr.isFnMacro(x); }
    boolean isScopeMacro(Macro m) { return m != null && (m.kind() == MacroKind.SCOPE_C || m.kind() == MacroKind.SCOPE_CXX); }
    void warn(int tok, String message) { tr.warn(h, tok, message); }
}
