package com.fintech.persistence.identity;

import java.io.Serializable;
import java.util.UUID;

public class HubAdminAssignmentId implements Serializable {

    private UUID hubId;
    private UUID userId;

    public HubAdminAssignmentId() {}

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HubAdminAssignmentId that)) {
            return false;
        }
        return hubId.equals(that.hubId) && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return hubId.hashCode() * 31 + userId.hashCode();
    }
}
