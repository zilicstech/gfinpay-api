package com.fintech.persistence.sales;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorAffiliateRepository extends JpaRepository<VendorAffiliateEntity, UUID> {}
