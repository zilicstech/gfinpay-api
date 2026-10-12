package com.fintech.persistence.ledger;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    /** Status only. Does not touch version or balances, so it cannot collide with a money movement. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WalletEntity w
               SET w.status = :status, w.active = :active, w.updatedAt = CURRENT_TIMESTAMP
             WHERE w.userId = :userId
            """)
    int updateStatus(@Param("userId") UUID userId, @Param("status") String status, @Param("active") boolean active);
}
