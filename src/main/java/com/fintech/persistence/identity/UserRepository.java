package com.fintech.persistence.identity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByCode(String code);

    boolean existsByMobile(String mobile);

    @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.id IN :ids AND u.userType = :userType")
    long countByIdsAndType(@Param("ids") Collection<UUID> ids, @Param("userType") String userType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.status = :status, u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id = :id
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.status = 'ACTIVE', u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id = :id AND u.userType = 'RETAILER'
            """)
    int activateRetailer(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.passwordHash = :passwordHash, u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id = :id
            """)
    int updatePassword(@Param("id") UUID id, @Param("passwordHash") String passwordHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.fullName = :fullName, u.email = :email, u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id = :id
            """)
    int updateProfile(@Param("id") UUID id, @Param("fullName") String fullName, @Param("email") String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.hubId = :hubId, u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id IN :ids
            """)
    int assignHub(@Param("ids") Collection<UUID> ids, @Param("hubId") UUID hubId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.hubId = COALESCE(u.hubId, :hubId), u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.id IN :ids
            """)
    int assignHubIfMissing(@Param("ids") Collection<UUID> ids, @Param("hubId") UUID hubId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
               SET u.hubId = :hubId, u.updatedAt = CURRENT_TIMESTAMP
             WHERE u.parentId IN :parentIds AND u.userType = 'RETAILER'
            """)
    int moveRetailersWithParents(@Param("parentIds") Collection<UUID> parentIds, @Param("hubId") UUID hubId);
}
