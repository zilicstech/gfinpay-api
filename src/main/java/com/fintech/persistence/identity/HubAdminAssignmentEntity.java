package com.fintech.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "hub_admin_assignments")
@IdClass(HubAdminAssignmentId.class)
public class HubAdminAssignmentEntity {

    @Id
    @Column(name = "hub_id")
    private UUID hubId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;
}
