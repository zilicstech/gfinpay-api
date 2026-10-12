package com.fintech.persistence.config;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionRuleRepository extends JpaRepository<CommissionRuleEntity, UUID> {}
