package com.fintech.persistence.finance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinanceRequestRepository extends JpaRepository<FinanceRequestEntity, UUID> {

    @Query("""
            SELECT COUNT(f) FROM FinanceRequestEntity f
             WHERE f.retailerUserId = :retailerId AND f.kind = 'ACTIVATION' AND f.status = 'RECEIVED'
            """)
    long countReceivedActivations(@Param("retailerId") UUID retailerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FinanceRequestEntity f
               SET f.status = 'RECEIVED', f.auditorUserId = :auditorId, f.auditorNotes = :notes,
                   f.reviewedAt = CURRENT_TIMESTAMP, f.walletTxnId = :txnId, f.updatedAt = CURRENT_TIMESTAMP
             WHERE f.id = :id
            """)
    int markReceived(@Param("id") UUID id, @Param("auditorId") UUID auditorId,
                     @Param("notes") String notes, @Param("txnId") UUID txnId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FinanceRequestEntity f
               SET f.status = 'REJECTED', f.auditorUserId = :auditorId, f.auditorNotes = :notes,
                   f.reviewedAt = CURRENT_TIMESTAMP, f.updatedAt = CURRENT_TIMESTAMP
             WHERE f.id = :id
            """)
    int markRejected(@Param("id") UUID id, @Param("auditorId") UUID auditorId, @Param("notes") String notes);
}
