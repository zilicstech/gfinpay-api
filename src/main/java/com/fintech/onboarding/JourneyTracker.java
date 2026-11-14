package com.fintech.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JourneyTracker {

    public static final String LINK_SENT = "LINK_SENT";
    public static final String LINK_OPENED = "LINK_OPENED";
    public static final String PHONE_VERIFIED = "PHONE_VERIFIED";
    public static final String LOCATION_CAPTURED = "LOCATION_CAPTURED";
    public static final String AADHAAR_VERIFIED = "AADHAAR_VERIFIED";
    public static final String PAN_VERIFIED = "PAN_VERIFIED";
    public static final String SELFIE_VERIFIED = "SELFIE_VERIFIED";
    public static final String CREDENTIALS_SET = "CREDENTIALS_SET";
    public static final String FIRST_LOGIN = "FIRST_LOGIN";
    public static final String ACTIVATION_SUBMITTED = "ACTIVATION_SUBMITTED";
    public static final String ACTIVATION_APPROVED = "ACTIVATION_APPROVED";
    public static final String ACTIVATION_REJECTED = "ACTIVATION_REJECTED";
    public static final String ACTIVATED = "ACTIVATED";
    public static final String BANK_VERIFIED = "BANK_VERIFIED";
    public static final String BUSINESS_SUBMITTED = "BUSINESS_SUBMITTED";
    public static final String BUSINESS_VERIFIED = "BUSINESS_VERIFIED";
    public static final String BUSINESS_REJECTED = "BUSINESS_REJECTED";
    public static final String AGREEMENT_GENERATED = "AGREEMENT_GENERATED";
    public static final String AGREEMENT_RELEASED = "AGREEMENT_RELEASED";
    public static final String AGREEMENT_SIGNED = "AGREEMENT_SIGNED";

    private static final List<String> STEP_ORDER = List.of(
            LINK_SENT, LINK_OPENED, PHONE_VERIFIED, LOCATION_CAPTURED,
            AADHAAR_VERIFIED, PAN_VERIFIED, SELFIE_VERIFIED, CREDENTIALS_SET,
            FIRST_LOGIN, ACTIVATION_SUBMITTED, ACTIVATION_APPROVED, ACTIVATED,
            BANK_VERIFIED, BUSINESS_SUBMITTED, BUSINESS_VERIFIED,
            AGREEMENT_GENERATED, AGREEMENT_RELEASED, AGREEMENT_SIGNED
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JourneyTracker(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID createJourney(UUID inviteId, UUID userId, String purpose, String initialStep) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO onboarding_journeys (id, invite_id, user_id, purpose, current_step, status)
                VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')
                """, id, inviteId, userId, purpose, initialStep);
        return id;
    }

    public void record(UUID journeyId, UUID userId, String step, String outcome,
                       Map<String, Object> detail, BigDecimal lat, BigDecimal lng,
                       String ip, String userAgent, boolean advanceStep) {
        jdbc.update("""
                INSERT INTO onboarding_journey_events
                    (journey_id, user_id, step, outcome, detail, latitude, longitude, ip, user_agent)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                journeyId, userId, step, outcome, toJson(detail), lat, lng,
                clip(ip, 120), clip(userAgent, 400));

        if (journeyId != null) {
            if (advanceStep && "SUCCESS".equals(outcome)) {
                String current = jdbc.queryForObject(
                        "SELECT current_step FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
                String nextStep = shouldAdvance(current, step) ? step : current;
                jdbc.update("""
                        UPDATE onboarding_journeys
                           SET current_step = ?, last_activity_at = now(),
                               user_id = COALESCE(user_id, ?),
                               status = CASE WHEN status = 'ABANDONED' THEN 'IN_PROGRESS' ELSE status END
                         WHERE id = ?
                        """, nextStep, userId, journeyId);
            } else {
                jdbc.update("""
                        UPDATE onboarding_journeys
                           SET last_activity_at = now(),
                               user_id = COALESCE(user_id, ?)
                         WHERE id = ?
                        """, userId, journeyId);
            }
        }
    }

    public void recordUserMilestone(UUID userId, String step, String outcome, Map<String, Object> detail) {
        if (userId == null) {
            return;
        }
        List<UUID> journeys = jdbc.queryForList("""
                SELECT id FROM onboarding_journeys
                 WHERE user_id = ?
                 ORDER BY started_at DESC
                 LIMIT 1
                """, UUID.class, userId);
        UUID journeyId = journeys.isEmpty() ? null : journeys.get(0);
        record(journeyId, userId, step, outcome, detail, null, null, null, null, "SUCCESS".equals(outcome));
    }

    @SuppressWarnings("unchecked")
    public int recordFailureAndCheckReview(UUID journeyId, UUID inviteId, String stepKey) {
        String raw = jdbc.queryForObject(
                "SELECT failure_counts::text FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        Map<String, Object> counts = new LinkedHashMap<>(fromJson(raw));
        int current = counts.get(stepKey) instanceof Number n ? n.intValue() : 0;
        int next = current + 1;
        counts.put(stepKey, next);
        boolean needsReview = next >= 3;
        jdbc.update("""
                UPDATE onboarding_journeys
                   SET failure_counts = ?::jsonb,
                       status = CASE WHEN ? THEN 'NEEDS_REVIEW' ELSE status END,
                       last_activity_at = now()
                 WHERE id = ?
                """, toJson(counts), needsReview, journeyId);
        if (needsReview && inviteId != null) {
            jdbc.update("""
                    UPDATE onboarding_invites
                       SET status = 'NEEDS_REVIEW', updated_at = now()
                     WHERE id = ? AND status IN ('SENT', 'IN_PROGRESS')
                    """, inviteId);
        }
        return next;
    }

    public void mergeIdentity(UUID journeyId, Map<String, Object> patch) {
        String raw = jdbc.queryForObject(
                "SELECT identity::text FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        Map<String, Object> map = new LinkedHashMap<>(fromJson(raw));
        map.putAll(patch);
        jdbc.update("""
                UPDATE onboarding_journeys
                   SET identity = ?::jsonb, last_activity_at = now()
                 WHERE id = ?
                """, toJson(map), journeyId);
    }

    public Map<String, Object> getIdentity(UUID journeyId) {
        String raw = jdbc.queryForObject(
                "SELECT identity::text FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        return fromJson(raw);
    }

    public List<Map<String, Object>> eventsForJourney(UUID journeyId) {
        return jdbc.queryForList("""
                SELECT id, step, outcome, detail::text AS detail_json, latitude, longitude, ip, user_agent, created_at
                  FROM onboarding_journey_events
                 WHERE journey_id = ?
                 ORDER BY created_at ASC, id ASC
                """, journeyId).stream().map(this::hydrateEvent).toList();
    }

    public List<Map<String, Object>> eventsForUser(UUID userId) {
        return jdbc.queryForList("""
                SELECT id, journey_id, step, outcome, detail::text AS detail_json, latitude, longitude, ip, user_agent, created_at
                  FROM onboarding_journey_events
                 WHERE user_id = ?
                    OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?)
                 ORDER BY created_at ASC, id ASC
                """, userId, userId).stream().map(this::hydrateEvent).toList();
    }

    private Map<String, Object> hydrateEvent(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        Object raw = out.remove("detail_json");
        out.put("detail", fromJson(raw == null ? null : String.valueOf(raw)));
        return out;
    }

    private static boolean shouldAdvance(String current, String candidate) {
        if (current == null || current.isBlank()) {
            return true;
        }
        int curIdx = STEP_ORDER.indexOf(current);
        int candIdx = STEP_ORDER.indexOf(candidate);
        if (curIdx < 0 || candIdx < 0) {
            return true;
        }
        return candIdx >= curIdx;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize journey JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String clip(String val, int max) {
        if (val == null) {
            return null;
        }
        return val.length() <= max ? val : val.substring(0, max);
    }
}

