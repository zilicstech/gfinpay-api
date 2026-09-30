package com.fintech.recon;

import com.fintech.fdcards.FdConversionCommission;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Until partner Excel parsers are live, a provider recon marks that provider's
 * generated-link FD sales as activated and pays the configured commission.
 */
@Service
public class FdProviderReconActivator {

    private static final Logger log = LoggerFactory.getLogger(FdProviderReconActivator.class);

    private final JdbcTemplate jdbc;
    private final FdConversionCommission fdCommission;

    public FdProviderReconActivator(JdbcTemplate jdbc, FdConversionCommission fdCommission) {
        this.jdbc = jdbc;
        this.fdCommission = fdCommission;
    }

    @Transactional
    public FdReconOutcome activateOpenLeads(String provider) {
        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT l.id, l.state, l.retailer_user_id, l.budget, i.provider, i.category_code
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                 WHERE i.category_code = 'FD_CARD'
                   AND (l.sale_provider = ? OR i.provider = ?)
                   AND l.payment_link_url IS NOT NULL
                   AND l.state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS', 'CONVERTED')
                 ORDER BY l.created_at
                """, provider, provider);
        int activated = 0;
        for (Map<String, Object> lead : leads) {
            UUID id = (UUID) lead.get("id");
            String prior = String.valueOf(lead.get("state"));
            int updated = jdbc.update("""
                    UPDATE sales_leads
                       SET state = 'ACTIVATED',
                           updated_at = now(),
                           state_history = state_history || jsonb_build_array(
                               jsonb_build_object('state', 'ACTIVATED', 'at', now(), 'source', 'RECON'))
                     WHERE id = ?
                       AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS', 'CONVERTED')
                    """, id);
            if (updated != 1) {
                continue;
            }
            if (!"CONVERTED".equals(prior)) {
                fdCommission.payOnConversion(lead);
            }
            activated++;
        }
        log.info("FD_RECON_ACTIVATE provider={} eligible={} activated={}", provider, leads.size(), activated);
        return new FdReconOutcome(leads.size(), activated, activated, 0, 0, "PROVIDER_DEFAULT", List.of(), List.of());
    }
}
