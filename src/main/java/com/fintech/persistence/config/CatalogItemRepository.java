package com.fintech.persistence.config;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogItemRepository extends JpaRepository<CatalogItemEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CatalogItemEntity i SET i.active = :active WHERE i.id = :id")
    int updateActive(@Param("id") UUID id, @Param("active") boolean active);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CatalogItemEntity i
               SET i.active = :active
             WHERE i.code = :code AND i.categoryCode = 'FD_CARD'
            """)
    int updateFdCardActive(@Param("code") String code, @Param("active") boolean active);
}
