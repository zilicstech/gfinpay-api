package com.fintech.persistence.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubRepository extends JpaRepository<HubEntity, UUID> {

    Optional<HubEntity> findByCode(String code);
}
