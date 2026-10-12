package com.fintech.persistence.sales;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorRepository extends JpaRepository<VendorEntity, UUID> {

    Optional<VendorEntity> findByCode(String code);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VendorEntity v
               SET v.status = :status, v.updatedAt = CURRENT_TIMESTAMP
             WHERE v.id = :id
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);
}
