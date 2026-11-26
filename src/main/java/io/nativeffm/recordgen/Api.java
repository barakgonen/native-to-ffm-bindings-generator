package io.nativeffm.recordgen;

import io.nativeffm.recordgen.Types.EnumInfo;
import io.nativeffm.recordgen.Types.StructInfo;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * The API classes: one per Java class the model names (the functions and constants of one or more headers). Each
 * wrapper function becomes a method that looks like the provider's function: how a parameter is passed follows from
 * its C type (ParamKind), and failures go to the SafeWrapper's error handler.
 */
final class Api {

    /** How a parameter is passed. */
    enum ParamKind {
        PASS,              // int, double, other pointers (MemorySegment), function pointers
        ENUM,              // a Java enum, passed as its value
        STRUCT_PTR,        // S * : S (input) and an overload with Ref<S> (in/out)
        STRUCT_CONST_PTR,  // const S * (and S passed by value in C++): S, input only
        STRUCT_ARRAY,      // S a[N]: NativeArray<S>, passed as is
        STRING,            // const char *: String
        PRIM_REF,          // int *: Ref<Integer>, in/out
        PTR_REF,           // void **: Ref<MemorySegment>, in/out
        PRIM_ARRAY         // double y[3]: double[], copied in (and back unless const)
    }

    enum RetKind { VOID, PASS, ENUM, STRUCT_VALUE, STRUCT_PTR, STRING }

    /** minSize: the array's element count (-1 if unknown); reference: never NULL (a C++ reference, a value). */
    record ApiParam(String javaName, Class<?> javaType, ParamKind kind, StructInfo struct, EnumInfo enumInfo,
                    long minSize, Jextract.Cell cell, boolean reference, boolean readOnly) {}

    /** cName: the wrapper function; provider: the function it calls, for error reports; resultCell: the result's layout. */
    record ApiFunction(String cName, String javaName, String prototype, Class<?> declaring, String method, RetKind ret,
                       Class<?> retType, StructInfo retStruct, EnumInfo retEnum, List<ApiParam> params, String provider,
                       String resultCell) {}

    /** A Java API class: functions and #define constants, of one or more headers. */
    record Facade(String name, List<ApiFunction> functions, Map<String, Long> constants, List<String> headers) {}

    private final Types types;
    private final Jextract jx;
    private final List<String> warnings;
    private final String prefix;

    Api(Types types, Jextract jx, String prefix, List<String> warnings) {
        this.types = types;
        this.jx = jx;
        this.prefix = prefix;
        this.warnings = warnings;
    }

    /** One class per Java class name of the model's headers, in header order; classes with nothing are left out. */
    List<Facade> facades(Model model) {
        Map<String, ApiFunction> byName = new LinkedHashMap<>();
        for (Model.Function f : model.functions()) {
            ApiFunction fn = function(f);
            if (fn != null) byName.put(f.safe(), fn);
        }
        Map<String, Facade> byClass = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>();
        for (Model.Header h : model.headers()) {
            Facade fa = byClass.computeIfAbsent(h.javaClass(), n -> new Facade(n, new ArrayList<>(), new LinkedHashMap<>(),
                    new ArrayList<>()));
            fa.headers().add(h.file());
            for (String f : h.functions()) {
                ApiFunction fn = byName.get(f);
                if (fn != null && claimed.add(f)) fa.functions().add(fn);
            }
            h.constants().forEach(fa.constants()::putIfAbsent);
        }
        List<Facade> facades = new ArrayList<>();
        for (Facade fa : byClass.values()) {
            if (fa.functions().isEmpty() && fa.constants().isEmpty()) continue;   // a header with types only
            Set<String> seen = new HashSet<>();
            for (ApiFunction fn : fa.functions()) {
                if (!seen.add(fn.javaName())) throw new GenException(fa.name() + ": two functions map to " + fn.javaName());
            }
            facades.add(fa);
        }
        return facades;
    }

    /** A wrapper function "int safe_F(<F's parameters>, R *result)" as a Java method F(<parameters>) returning R. */
    private ApiFunction function(Model.Function f) {
        int count = f.params().size() + (f.result() != null ? 1 : 0);
        Method m = jx.method(f.safe(), count);
        if (m == null) {                                // under an #if that is false for jextract: it did not see it
            if (f.condition() == null) warnings.add(f.safe() + ": could not match the jextract method signature, skipped");
            return null;
        }
        Class<?> declaring = m.getDeclaringClass();
        Class<?>[] javaTypes = m.getParameterTypes();
        Parameter[] reflected = m.getParameters();
        List<ApiParam> params = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < f.params().size(); i++) {
            Model.Param p = f.params().get(i);
            Model.Type ct = p.type();
            Class<?> jt = javaTypes[i];
            String name = Types.memberName(reflected[i].isNamePresent() ? reflected[i].getName() : p.name());
            while (!names.add(name)) name += "_";
            long arraySize = p.array() == null ? -2 : p.array();            // -2: not an array
            ParamKind kind = ParamKind.PASS;
            StructInfo st = null;
            EnumInfo en = null;
            Jextract.Cell cell = null;
            boolean readOnly = false;
            if (jt == int.class && ct.ptr() == 0 && types.enums.containsKey(ct.name())) {
                kind = ParamKind.ENUM;
                en = types.enums.get(ct.name());
            } else if (!jt.isPrimitive() && !ct.fn()) {
                StructInfo target = types.struct(ct.name());
                if (ct.ptr() == 1 && target != null) {
                    kind = arraySize != -2 ? ParamKind.STRUCT_ARRAY : ct.isConst() ? ParamKind.STRUCT_CONST_PTR : ParamKind.STRUCT_PTR;
                    st = target;
                } else if (ct.ptr() == 1 && ct.isChar() && ct.isConst()) {
                    kind = ParamKind.STRING;
                } else if (ct.ptr() == 1 && arraySize != -2 && (cell = jx.primitive(ct.name(), declaring)) != null) {
                    kind = ParamKind.PRIM_ARRAY;                            // double y[3] -> double[]
                    readOnly = ct.isConst();
                } else if (ct.ptr() == 1 && !ct.isConst() && arraySize == -2 && (cell = jx.primitive(ct.name(), declaring)) != null) {
                    kind = ParamKind.PRIM_REF;                              // int *count -> Ref<Integer>
                } else if (ct.ptr() == 2 && ct.name().equals("void")) {
                    kind = ParamKind.PTR_REF;                               // void **out -> Ref<MemorySegment>
                }
            }
            // a C++ reference, or a struct passed by value (a const pointer in the safe wrapper API): never null
            boolean reference = p.ref() || p.byValue() && kind == ParamKind.STRUCT_CONST_PTR;
            params.add(new ApiParam(name, jt, kind, st, en, arraySize, cell, reference, readOnly));
        }

        RetKind rk;
        StructInfo rs = null;
        EnumInfo re = null;
        String resultCell = null;
        Class<?> retClass;
        Model.Type ret = f.result();
        if (ret == null) {
            rk = RetKind.VOID;
            retClass = void.class;
        } else if (ret.ptr() == 0 && types.enums.containsKey(ret.name())) {
            rk = RetKind.ENUM;
            re = types.enums.get(ret.name());
            resultCell = "ValueLayout.JAVA_INT";
            retClass = int.class;
        } else if (ret.ptr() == 0 && types.struct(ret.name()) != null) {
            rk = RetKind.STRUCT_VALUE;
            rs = types.struct(ret.name());
            resultCell = rs.ref() + ".LAYOUT";
            retClass = m.getReturnType();
        } else if (ret.ptr() == 1 && types.struct(ret.name()) != null) {
            rk = RetKind.STRUCT_PTR;                                        // T* or T& (never NULL)
            rs = types.struct(ret.name());
            resultCell = "ValueLayout.ADDRESS";
            retClass = m.getReturnType();
        } else if (ret.ptr() == 1 && ret.isChar() && ret.isConst()) {
            rk = RetKind.STRING;
            resultCell = "ValueLayout.ADDRESS";
            retClass = m.getReturnType();
        } else if (ret.ptr() == 0) {
            Jextract.Cell c = jx.primitive(ret.name(), declaring);
            if (c == null) {
                String declared = f.decl().substring(0, Math.max(0, f.decl().indexOf(f.provider() + "("))).strip();
                warnings.add(f.safe() + ": unsupported result type '" + declared.replaceAll("\\b\\w+::", "") + "', skipped");
                return null;
            }
            rk = RetKind.PASS;
            resultCell = c.layoutExpr();
            retClass = c.carrier();
        } else {
            rk = RetKind.PASS;                                              // other pointers: MemorySegment
            resultCell = "ValueLayout.ADDRESS";
            retClass = MemorySegment.class;
        }
        String javaName = Types.memberName(f.safe().substring(prefix.length()));
        return new ApiFunction(f.safe(), javaName, f.decl(), declaring, m.getName(), rk, retClass, rs, re, params,
                f.provider(), resultCell);
    }
}
