package com.loggingx.ai;

/**
 * An event of a streamed answer: any number of {@link Delta}s followed by one {@link Done}.
 */
public sealed interface AiChatStreamEvent {

    /**
     * The next piece of the answer's text.
     */
    record Delta(String content) implements AiChatStreamEvent {
    }

    /**
     * The end of the stream, with the whole answer and, where the provider reports them, the model and token counts.
     */
    record Done(AiChatReply reply) implements AiChatStreamEvent {
    }
}
