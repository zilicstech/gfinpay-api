package com.fintech.verification;

import com.fintech.identity.AdminAccess;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.security.SessionService;
import com.fintech.platform.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FaceReviewService {

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final SessionService sessions;
    private final JourneyTracker journeyTracker;

    public FaceReviewService(
            JdbcTemplate jdbc,
            AdminAccess access,
            SessionService sessions,
            JourneyTracker journeyTracker) {
        this.jdbc = jdbc;
        this.access = access;
        this.sessions = sessions;
        this.journeyTracker = journeyTracker;
    }

    @Transactional
    public void review(AuthPrincipal admin, UUID userId, boolean matched, String note) {
        if (admin == null || !admin.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only an admin can review face verification");
        }
        access.assertNetworkUser(userId);

        Map<String, Object> profileRow;
        try {
            profileRow = jdbc.queryForMap(
                    "SELECT selfie_key, face_review_status FROM agent_kyc_profiles WHERE user_id = ?",
                    userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND",
                    "Verification profile was not found");
        }

        String selfieKey = (String) profileRow.get("selfie_key");
        if (selfieKey == null || selfieKey.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_NOT_CAPTURED",
                    "Partner has not captured a live selfie yet");
        }

        String currentStatus = profileRow.get("face_review_status") == null
                ? "PENDING"
                : String.valueOf(profileRow.get("face_review_status"));
        if (!"PENDING".equalsIgnoreCase(currentStatus)) {
            throw ApiException.of(HttpStatus.CONFLICT, "FACE_ALREADY_REVIEWED",
                    "Face verification has already been marked as " + currentStatus + " and cannot be modified");
        }

        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (!matched && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a reason when marking a face mismatch");
        }

        if (matched) {
            int updated = jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET face_review_status = 'MATCHED',
                           face_reviewed_by = ?,
                           face_reviewed_at = now(),
                           face_review_note = ?,
                           updated_at = now()
                     WHERE user_id = ?
                       AND face_review_status = 'PENDING'
                    """, admin.userId(), cleanNote, userId);
            if (updated == 0) {
                throw ApiException.of(HttpStatus.CONFLICT, "FACE_ALREADY_REVIEWED",
                        "Face verification has already been reviewed and cannot be modified");
            }
        } else {
            int updated = jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET face_review_status = 'MISMATCH',
                           face_reviewed_by = ?,
                           face_reviewed_at = now(),
                           face_review_note = ?,
                           identity_status = 'REVERIFY_REQUIRED',
                           personal_identity_verified = FALSE,
                           personal_status = 'NOT_VERIFIED',
                           updated_at = now()
                     WHERE user_id = ?
                       AND face_review_status = 'PENDING'
                    """, admin.userId(), cleanNote, userId);
            if (updated == 0) {
                throw ApiException.of(HttpStatus.CONFLICT, "FACE_ALREADY_REVIEWED",
                        "Face verification has already been reviewed and cannot be modified");
            }
            sessions.revokeAllForUser(userId, "FACE_REVIEW_MISMATCH");
        }

        journeyTracker.recordUserMilestone(
                userId,
                "FACE_REVIEW",
                matched ? "SUCCESS" : "FAILURE",
                Map.of(
                        "matched", matched,
                        "reviewedBy", admin.userId().toString(),
                        "note", cleanNote == null ? "" : cleanNote));
    }
}

