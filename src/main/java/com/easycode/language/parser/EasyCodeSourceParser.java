package com.easycode.language.parser;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.builtin.PrintInstruction;
import com.easycode.language.builtin.SetVariableInstruction;
import com.easycode.language.model.EasyCodeProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parses the small, line-oriented source form of the current easyCode language. */
public final class EasyCodeSourceParser {
    public EasyCodeProgram parse(String source) {
        Objects.requireNonNull(source, "source");
        List<EasyCodeInstruction> instructions = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            instructions.add(parseInstruction(line, lineNumber));
        }
        return EasyCodeProgram.of(instructions);
    }

    private static EasyCodeInstruction parseInstruction(String line, int lineNumber) {
        if (line.startsWith("set ")) {
            return parseSet(line.substring(4).trim(), lineNumber);
        }
        if (line.startsWith("println ")) {
            return PrintInstruction.line("println-" + lineNumber,
                    parseText(line.substring(8).trim(), lineNumber));
        }
        if (line.startsWith("print ")) {
            return new PrintInstruction("print-" + lineNumber,
                    parseText(line.substring(6).trim(), lineNumber), false);
        }
        throw error(lineNumber, "expected set, print, or println instruction");
    }

    private static SetVariableInstruction parseSet(String source, int lineNumber) {
        int separator = source.indexOf('=');
        if (separator <= 0 || separator == source.length() - 1) {
            throw error(lineNumber, "set syntax is: set <name> = <value>");
        }
        String name = source.substring(0, separator).trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw error(lineNumber, "invalid variable name: " + name);
        }
        return new SetVariableInstruction("set-" + lineNumber, name,
                parseLiteral(source.substring(separator + 1).trim(), lineNumber));
    }

    private static Object parseLiteral(String value, int lineNumber) {
        if (value.startsWith("\"") || value.endsWith("\"")) {
            return parseQuoted(value, lineNumber);
        }
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        if (value.equals("null")) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw error(lineNumber, "unsupported literal: " + value);
        }
    }

    private static String parseText(String value, int lineNumber) {
        if (value.isEmpty()) {
            throw error(lineNumber, "print text must not be empty");
        }
        return value.startsWith("\"") ? parseQuoted(value, lineNumber) : value;
    }

    private static String parseQuoted(String value, int lineNumber) {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            throw error(lineNumber, "quoted text must start and end with a double quote");
        }
        StringBuilder result = new StringBuilder();
        boolean escaping = false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (escaping) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> throw error(lineNumber, "unsupported escape: \\" + character);
                });
                escaping = false;
            } else if (character == '\\') {
                escaping = true;
            } else {
                result.append(character);
            }
        }
        if (escaping) {
            throw error(lineNumber, "unterminated escape sequence");
        }
        return result.toString();
    }

    private static EasyCodeParseException error(int lineNumber, String message) {
        return new EasyCodeParseException(lineNumber, message);
    }
}
