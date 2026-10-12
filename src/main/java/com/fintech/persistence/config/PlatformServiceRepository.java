package com.fintech.persistence.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformServiceRepository extends JpaRepository<PlatformServiceEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PlatformServiceEntity s
               SET s.enabled = :enabled, s.updatedAt = CURRENT_TIMESTAMP
             WHERE s.code = :code
            """)
    int updateEnabled(@Param("code") String code, @Param("enabled") boolean enabled);
}
