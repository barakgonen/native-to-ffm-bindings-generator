package io.nativeffm.recordgen;

/** An error that stops RecordGen: on the command line "RecordGen: &lt;message&gt;" and exit code 1, in Maven a build failure. */
public final class GenException extends RuntimeException {
    GenException(String message) {
        super(message);
    }
}
