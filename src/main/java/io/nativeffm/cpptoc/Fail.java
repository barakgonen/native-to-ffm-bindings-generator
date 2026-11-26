package io.nativeffm.cpptoc;

/**
 * An error that stops CppToC or BuildSafeLib: on the command line "CppToC: &lt;message&gt;" and exit code 1, in Maven a
 * build failure. Inside a declaration: the declaration is skipped with a warning.
 */
public final class Fail extends RuntimeException {
    Fail(String message) {
        super(message);
    }
}
