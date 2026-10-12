package com.fintech.persistence.sales;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    boolean existsByMobile(String mobile);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO customer_category_eligibility (customer_id, category_code, status, reason, checked_at)
            SELECT :customerId, code, :status, :reason, now() FROM catalog_categories
            ON CONFLICT (customer_id, category_code)
            DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason, checked_at = now()
            """, nativeQuery = true)
    void upsertEligibility(@Param("customerId") UUID customerId,
                           @Param("status") String status,
                           @Param("reason") String reason);
}
