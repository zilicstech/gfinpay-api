package com.fintech.persistence.finance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconRowRepository extends JpaRepository<ReconRowEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ReconRowEntity r WHERE r.batchId = :batchId")
    int deleteByBatchId(@Param("batchId") UUID batchId);
}
