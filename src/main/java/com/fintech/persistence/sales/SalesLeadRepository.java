package com.fintech.persistence.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesLeadRepository extends JpaRepository<SalesLeadEntity, UUID> {

    @Query("""
            SELECT s FROM SalesLeadEntity s
             WHERE s.customerId = :customerId
             ORDER BY s.createdAt DESC
            """)
    List<SalesLeadEntity> findForCustomer(@Param("customerId") UUID customerId);

    @Query("""
            SELECT s FROM SalesLeadEntity s
             WHERE s.retailerUserId = :retailerUserId AND s.saleChannel = 'INTERNAL'
             ORDER BY s.createdAt DESC
            """)
    List<SalesLeadEntity> findInternalForRetailer(@Param("retailerUserId") UUID retailerUserId);
}
