package com.fintech.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(name = "user_type", nullable = false, columnDefinition = "text")
    private String userType;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "hub_id")
    private UUID hubId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "full_name", nullable = false, columnDefinition = "text")
    private String fullName;

    @Column(nullable = false, unique = true, length = 15)
    private String mobile;

    @Column(unique = true, columnDefinition = "text")
    private String email;

    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    @Column(columnDefinition = "text")
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public String getUserType() {
        return userType;
    }

    public String getStatus() {
        return status;
    }
}
