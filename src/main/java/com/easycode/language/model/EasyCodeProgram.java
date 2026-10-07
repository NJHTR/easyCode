package com.easycode.language.model;

import com.easycode.language.api.EasyCodeInstruction;

import java.util.List;
import java.util.Objects;

/** Immutable instruction sequence representing one easyCode program. */
public record EasyCodeProgram(List<EasyCodeInstruction> instructions) {
    public EasyCodeProgram {
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
        if (instructions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("instructions cannot contain null");
        }
    }

    public static EasyCodeProgram of(List<EasyCodeInstruction> instructions) {
        return new EasyCodeProgram(instructions);
    }
}
