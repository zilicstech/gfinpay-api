package com.fintech.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One activity row, built on the request thread and inserted later. */
public record ActivityEvent(
        Instant occurredAt,
        UUID actorUserId,
        String actorUserType,
        String action,
        String subjectType,
        String subjectId,
        String outcome,
        int httpStatus,
        String httpMethod,
        String httpPath,
        Map<String, Object> detail,
        String ip,
        String userAgent) {}
