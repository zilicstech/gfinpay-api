package com.fintech.persistence.finance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentKycProfileRepository extends JpaRepository<AgentKycProfileEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentKycProfileEntity k
               SET k.kycStatus = :status, k.rejectionReason = :reason, k.updatedAt = CURRENT_TIMESTAMP
             WHERE k.userId = :userId
            """)
    int updateStatus(@Param("userId") UUID userId, @Param("status") String status, @Param("reason") String reason);
}
