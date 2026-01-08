package com.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** One OpenAI-compatible model turn used by the bounded role Agent loop. */
public interface AgentModelTransport {
    Completion complete(Request request);
    default String mode() {
        return "modelport";
    }
    default Description describe(String role) {
        boolean replay = "replay".equals(mode());
        return new Description(
                replay ? "deterministic-replay" : "modelport",
                replay ? "deterministic-replay-" + role : "modelport-default",
                true);
    }



    record Request(
            String runId,
            String role,
            int turn,
            List<Message> messages,
            List<ToolDefinition> tools
    ) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record Message(
            String role,
            String content,
            String toolCallId,
            List<ToolCall> toolCalls
    ) {
        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String content) {
            return new Message("system", content, null, List.of());
        }

        public static Message user(String content) {
            return new Message("user", content, null, List.of());
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, List.of());
        }
    }

    record ToolDefinition(String name, String description, JsonNode parameters) {
    }

    record ToolCall(String id, String name, JsonNode arguments) {
    }
    record Description(String provider, String model, boolean localOnly) {
    }


    record Completion(
            String content,
            List<ToolCall> toolCalls,
            Usage usage
    ) {
        public Completion {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            usage = usage == null ? Usage.ZERO : usage;
        }
    }

    record Usage(int inputTokens, int outputTokens, int totalTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0);
    }

    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }
}
