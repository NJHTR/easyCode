package com.easycode.llm.api;

import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;

/** Provider-independent boundary for one synchronous model call. */
@FunctionalInterface
public interface LlmProvider {
    LlmResponse generate(LlmRequest request) throws LlmException;
}
