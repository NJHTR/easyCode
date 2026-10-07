package com.easycode.language.parser;

/** Reports invalid source text at the easyCode language boundary. */
public final class EasyCodeParseException extends IllegalArgumentException {
    public EasyCodeParseException(int lineNumber, String message) {
        super("line " + lineNumber + ": " + message);
    }
}
