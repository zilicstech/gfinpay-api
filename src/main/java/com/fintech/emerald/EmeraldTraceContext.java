package com.fintech.emerald;

import java.util.UUID;

/**
 * Thread-local correlation context propagating business metadata ({@code userId}, {@code flow}, {@code externalRef})
 * down to {@link EmeraldClient} so every outbound Emerald HTTP log and {@code emerald_api_logs} row is automatically
 * linked to the originating retailer and business operation.
 */
public final class EmeraldTraceContext {

    public record Snapshot(UUID userId, String flow, String externalRef) {}

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private EmeraldTraceContext() {}

    public static Scope open(UUID userId, String flow, String externalRef) {
        Snapshot previous = CURRENT.get();
        UUID mergedUser = userId != null ? userId : (previous != null ? previous.userId() : null);
        String mergedFlow = (flow != null && !flow.isBlank()) ? flow : (previous != null ? previous.flow() : null);
        String mergedRef = (externalRef != null && !externalRef.isBlank())
                ? externalRef
                : (previous != null ? previous.externalRef() : null);
        CURRENT.set(new Snapshot(mergedUser, mergedFlow, mergedRef));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Scope open(UUID userId, String flow) {
        return open(userId, flow, null);
    }

    public static Snapshot current() {
        return CURRENT.get();
    }
}

