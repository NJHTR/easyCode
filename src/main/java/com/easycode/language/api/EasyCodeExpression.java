package com.easycode.language.api;

import com.easycode.language.runtime.EasyCodeExecutionContext;

/** Computes a language value from the current easyCode execution state. */
@FunctionalInterface
public interface EasyCodeExpression<T> {
    T evaluate(EasyCodeExecutionContext context) throws Exception;
}
