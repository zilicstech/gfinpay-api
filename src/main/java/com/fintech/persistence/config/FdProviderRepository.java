package com.fintech.persistence.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FdProviderRepository extends JpaRepository<FdProviderEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FdProviderEntity p
               SET p.enabled = :enabled, p.updatedAt = CURRENT_TIMESTAMP
             WHERE p.code = :code
            """)
    int updateEnabled(@Param("code") String code, @Param("enabled") boolean enabled);
}
