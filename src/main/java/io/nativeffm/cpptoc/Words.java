package io.nativeffm.cpptoc;

import java.util.*;
import java.util.regex.Pattern;

/** Fixed word lists: C and C++ keywords, C standard types and headers, and what the C library headers declare. */
final class Words {

    private Words() {
    }

    static final Set<String> PRIM_WORDS = Set.of("void", "char", "short", "int", "long", "float", "double",
            "signed", "unsigned", "bool", "_Bool", "wchar_t", "__int64", "__int128");
    static final Set<String> C_STD_TYPES = new HashSet<>(List.of("size_t", "ptrdiff_t", "max_align_t",
            "intptr_t", "uintptr_t", "intmax_t", "uintmax_t", "wint_t", "FILE", "time_t", "clock_t", "va_list"));
    static {
        for (String bits : List.of("8", "16", "32", "64")) {
            for (String kind : List.of("", "_least", "_fast")) {
                C_STD_TYPES.add("int" + kind + bits + "_t");
                C_STD_TYPES.add("uint" + kind + bits + "_t");
            }
        }
    }
    /** Underlying types of an enum that keep it int-sized: such an enum is a C enum (and a Java enum). */
    static final Set<String> INT_LIKE = Set.of("int", "signed", "signed int", "unsigned", "unsigned int",
            "int32_t", "uint32_t");
    static final Map<String, String> C_HEADERS = Map.ofEntries(
            Map.entry("cstdint", "stdint.h"), Map.entry("cstddef", "stddef.h"), Map.entry("cstdbool", "stdbool.h"),
            Map.entry("climits", "limits.h"), Map.entry("cfloat", "float.h"), Map.entry("cstdio", "stdio.h"),
            Map.entry("cstdlib", "stdlib.h"), Map.entry("cstring", "string.h"), Map.entry("cmath", "math.h"),
            Map.entry("cstdarg", "stdarg.h"), Map.entry("cwchar", "wchar.h"), Map.entry("cinttypes", "inttypes.h"),
            Map.entry("cctype", "ctype.h"), Map.entry("ctime", "time.h"), Map.entry("cassert", "assert.h"),
            Map.entry("cerrno", "errno.h"), Map.entry("csignal", "signal.h"), Map.entry("clocale", "locale.h"),
            Map.entry("cuchar", "uchar.h"));
    /**
     * What each C library header declares that a header could use (types, macros). A C view keeps such an include
     * only if the header uses one of these names: MATLAB's &lt;cstdlib&gt; is for the implementation, and would bring
     * half of libc (and its unsupported types) into jextract's output.
     */
    static final Map<String, Pattern> C_HEADER_NAMES = Map.ofEntries(
            Map.entry("stddef.h", Pattern.compile("size_t|ptrdiff_t|NULL|offsetof|max_align_t|wchar_t|nullptr_t")),
            Map.entry("stdint.h", Pattern.compile("u?int(_least|_fast)?\\d+_t|u?int(ptr|max)_t|U?INT\\w*_(MAX|MIN|C)"
                    + "|SIZE_MAX|PTRDIFF_(MAX|MIN)|SIG_ATOMIC_\\w+|WCHAR_(MAX|MIN)|WINT_(MAX|MIN)")),
            Map.entry("stdbool.h", Pattern.compile("bool|true|false|__bool_true_false_are_defined")),
            Map.entry("stdlib.h", Pattern.compile("size_t|wchar_t|NULL|l?l?div_t|EXIT_SUCCESS|EXIT_FAILURE|RAND_MAX"
                    + "|MB_CUR_MAX|malloc|calloc|realloc|free|abort|exit|abs|labs|atoi|atof|strtol|strtod")),
            Map.entry("stdio.h", Pattern.compile("FILE|fpos_t|EOF|BUFSIZ|SEEK_\\w+|stdin|stdout|stderr|\\w*printf")),
            Map.entry("string.h", Pattern.compile("size_t|NULL|mem\\w+|str\\w+")),
            Map.entry("math.h", Pattern.compile("HUGE_VALF?L?|INFINITY|NAN|M_\\w+|float_t|double_t|FP_\\w+"
                    + "|sqrt|pow|exp|log|sin|cos|tan|fabs|floor|ceil|fmod|isnan|isinf")),
            Map.entry("limits.h", Pattern.compile("CHAR_BIT|MB_LEN_MAX|U?S?(CHAR|SHRT|INT|LONG|LLONG)_(MAX|MIN)")),
            Map.entry("float.h", Pattern.compile("(FLT|DBL|LDBL)_\\w+|FLT_RADIX|DECIMAL_DIG")),
            Map.entry("time.h", Pattern.compile("time_t|clock_t|tm|timespec|CLOCKS_PER_SEC|time|clock")),
            Map.entry("stdarg.h", Pattern.compile("va_list|va_start|va_arg|va_end|va_copy")),
            Map.entry("wchar.h", Pattern.compile("wchar_t|wint_t|mbstate_t|WEOF|WCHAR_(MAX|MIN)")),
            Map.entry("uchar.h", Pattern.compile("char16_t|char32_t|char8_t")),
            Map.entry("assert.h", Pattern.compile("assert|static_assert")),
            Map.entry("errno.h", Pattern.compile("errno|E[A-Z0-9]+")),
            Map.entry("inttypes.h", Pattern.compile("PRI\\w+|SCN\\w+|imaxdiv_t|u?int(_least|_fast)?\\d+_t"
                    + "|u?int(ptr|max)_t")),
            Map.entry("ctype.h", Pattern.compile("is\\w+|to(upper|lower)")),
            Map.entry("signal.h", Pattern.compile("sig_atomic_t|SIG\\w+|signal|raise")),
            Map.entry("locale.h", Pattern.compile("lconv|LC_\\w+|setlocale|localeconv")));

    /** Identifiers followed by a parenthesized group that are not function names. */
    static final Set<String> GROUP_ATTRS = Set.of("__attribute__", "__declspec", "alignas", "_Alignas",
            "__pragma", "_Pragma", "__asm__", "asm");
    static final Set<String> CALLCONV = Set.of("__cdecl", "__stdcall", "__fastcall", "__vectorcall",
            "__thiscall", "__regcall");
    static final Set<String> NOT_FUNCTION = new HashSet<>(List.of("decltype", "sizeof", "alignof", "noexcept",
            "throw", "static_assert", "typeid", "return", "if", "while", "for", "switch"));
    static {
        NOT_FUNCTION.addAll(GROUP_ATTRS);
        NOT_FUNCTION.addAll(PRIM_WORDS);
        NOT_FUNCTION.addAll(CALLCONV);
    }
}
