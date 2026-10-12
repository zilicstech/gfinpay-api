package com.fintech.persistence.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserSessionEntity s
               SET s.revokedAt = CURRENT_TIMESTAMP, s.revokedReason = :reason
             WHERE s.id = :id AND s.revokedAt IS NULL
            """)
    int revoke(@Param("id") UUID id, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserSessionEntity s
               SET s.revokedAt = CURRENT_TIMESTAMP, s.revokedReason = :reason
             WHERE s.userId = :userId AND s.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("reason") String reason);
}
