package com.fintech.persistence.finance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconBatchRepository extends JpaRepository<ReconBatchEntity, UUID> {}
