package com.fintech.recon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.fdcards.FdConversionCommission;
import com.fintech.platform.web.ApiException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FdLeadReconApplier {

    private final JdbcTemplate jdbc;
    private final FdConversionCommission fdCommission;
    private final ObjectMapper objectMapper;

    public FdLeadReconApplier(JdbcTemplate jdbc, FdConversionCommission fdCommission, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.fdCommission = fdCommission;
        this.objectMapper = objectMapper;
    }

    public record ApplyResult(boolean matched, boolean newlyActivated, boolean lifecycleTouched) {}

    @Transactional
    public ApplyResult apply(Map<String, Object> lead, FdMisRow row) {
        UUID leadId = (UUID) lead.get("id");
        String priorState = String.valueOf(lead.get("state"));
        if (row.partnerStatus() != null || row.partnerSnapshot() != null) {
            jdbc.update("""
                    UPDATE sales_leads
                       SET partner_status = COALESCE(?, partner_status),
                           partner_status_at = COALESCE(?, partner_status_at),
                           partner_user_id = COALESCE(?, partner_user_id),
                           partner_snapshot = COALESCE(?::jsonb, partner_snapshot),
                           updated_at = now()
                     WHERE id = ?
                    """,
                    row.partnerStatus(),
                    row.partnerStatusAt() == null ? null : Timestamp.from(row.partnerStatusAt()),
                    blankToNull(row.partnerUserId()),
                    snapshotJson(row.partnerSnapshot()),
                    leadId);
        }
        FdPartnerLifecycle lifecycle = row.lifecycle();
        if (lifecycle == FdPartnerLifecycle.NONE) {
            return new ApplyResult(true, false, false);
        }
        String targetState = targetLifecycleState(priorState, lifecycle);
        if (targetState == null) {
            appendHistoryOnly(leadId, row);
            return new ApplyResult(true, false, false);
        }
        if (targetState.equals(priorState) && !"ACTIVATED".equals(targetState)) {
            appendHistoryOnly(leadId, row);
            return new ApplyResult(true, false, true);
        }
        boolean activate = "ACTIVATED".equals(targetState) && !"ACTIVATED".equals(priorState);
        if (activate) {
            int updated = jdbc.update("""
                    UPDATE sales_leads
                       SET state = 'ACTIVATED',
                           updated_at = now(),
                           state_history = state_history || jsonb_build_array(
                               jsonb_build_object(
                                   'state', 'ACTIVATED',
                                   'at', now(),
                                   'source', 'RECON',
                                   'sheet', ?,
                                   'partner_user_id', ?,
                                   'column', ?,
                                   'partner_status', ?,
                                   'partner_status_at', ?))
                     WHERE id = ?
                       AND state NOT IN ('ACTIVATED', 'REJECTED', 'EXPIRED')
                    """,
                    row.sheetName(),
                    row.partnerUserId(),
                    row.sourceColumn(),
                    row.partnerStatus(),
                    row.partnerStatusAt() == null ? null : row.partnerStatusAt().toString(),
                    leadId);
            if (updated == 1 && !commissionAlreadyPaid(leadId, lead) && payCommission(lead)) {
                fdCommission.payOnConversion(lead);
            }
            return new ApplyResult(true, updated == 1, true);
        }
        if ("OPENED".equals(targetState) && "LINK_CREATED".equals(priorState)) {
            jdbc.update("""
                    UPDATE sales_leads
                       SET state = 'OPENED',
                           updated_at = now(),
                           state_history = state_history || jsonb_build_array(
                               jsonb_build_object(
                                   'state', 'OPENED',
                                   'at', now(),
                                   'source', 'RECON',
                                   'sheet', ?,
                                   'partner_user_id', ?,
                                   'column', ?,
                                   'partner_status', ?))
                     WHERE id = ? AND state = 'LINK_CREATED'
                    """,
                    row.sheetName(),
                    row.partnerUserId(),
                    row.sourceColumn(),
                    row.partnerStatus(),
                    leadId);
            return new ApplyResult(true, false, true);
        }
        if ("IN_PROGRESS".equals(targetState) && isOpenish(priorState)) {
            jdbc.update("""
                    UPDATE sales_leads
                       SET state = 'IN_PROGRESS',
                           updated_at = now(),
                           state_history = state_history || jsonb_build_array(
                               jsonb_build_object(
                                   'state', 'IN_PROGRESS',
                                   'at', now(),
                                   'source', 'RECON',
                                   'sheet', ?,
                                   'partner_user_id', ?,
                                   'column', ?,
                                   'partner_status', ?))
                     WHERE id = ?
                       AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS', 'CONVERTED', 'FAILED')
                    """,
                    row.sheetName(),
                    row.partnerUserId(),
                    row.sourceColumn(),
                    row.partnerStatus(),
                    leadId);
            return new ApplyResult(true, false, true);
        }
        if ("FAILED".equals(targetState) && !"FAILED".equals(priorState)) {
            jdbc.update("""
                    UPDATE sales_leads
                       SET state = 'FAILED',
                           updated_at = now(),
                           state_history = state_history || jsonb_build_array(
                               jsonb_build_object(
                                   'state', 'FAILED',
                                   'at', now(),
                                   'source', 'RECON',
                                   'sheet', ?,
                                   'partner_user_id', ?,
                                   'column', ?,
                                   'partner_status', ?,
                                   'partner_status_at', ?))
                     WHERE id = ?
                       AND state NOT IN ('ACTIVATED', 'REJECTED', 'EXPIRED')
                    """,
                    row.sheetName(),
                    row.partnerUserId(),
                    row.sourceColumn(),
                    row.partnerStatus(),
                    row.partnerStatusAt() == null ? null : row.partnerStatusAt().toString(),
                    leadId);
            return new ApplyResult(true, false, true);
        }
        appendHistoryOnly(leadId, row);
        return new ApplyResult(true, false, "ACTIVATED".equals(priorState));
    }

    private static String targetLifecycleState(String priorState, FdPartnerLifecycle lifecycle) {
        if ("REJECTED".equals(priorState) || "EXPIRED".equals(priorState)) {
            return null;
        }
        if ("ACTIVATED".equals(priorState)) {
            return null;
        }
        return switch (lifecycle) {
            case OPENED -> "OPENED";
            case IN_PROGRESS -> "IN_PROGRESS";
            case ACTIVATED -> "ACTIVATED";
            case FAILED -> "FAILED";
            default -> null;
        };
    }

    private static boolean isOpenish(String state) {
        return "LINK_CREATED".equals(state) || "OPENED".equals(state)
                || "IN_PROGRESS".equals(state) || "CONVERTED".equals(state)
                || "FAILED".equals(state);
    }

    private void appendHistoryOnly(UUID leadId, FdMisRow row) {
        if (row.partnerStatus() == null) {
            return;
        }
        jdbc.update("""
                UPDATE sales_leads
                   SET state_history = state_history || jsonb_build_array(
                       jsonb_build_object(
                           'at', now(),
                           'source', 'RECON',
                           'sheet', ?,
                           'partner_user_id', ?,
                           'column', ?,
                           'partner_status', ?,
                           'partner_status_at', ?))
                 WHERE id = ?
                """,
                row.sheetName(),
                row.partnerUserId(),
                row.sourceColumn(),
                row.partnerStatus(),
                row.partnerStatusAt() == null ? null : row.partnerStatusAt().toString(),
                leadId);
    }

    private boolean commissionAlreadyPaid(UUID leadId, Map<String, Object> lead) {
        String provider = lead.get("provider") == null ? null : String.valueOf(lead.get("provider"));
        if (provider == null || provider.isBlank() || "null".equals(provider)) {
            provider = lead.get("sale_provider") == null ? "ZET" : String.valueOf(lead.get("sale_provider"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT 1
                  FROM transactions t
                  JOIN fd_providers p ON p.commission_txn_type = t.txn_type::text
                 WHERE t.partner_ref = ?
                   AND t.state = 'SUCCESS'
                   AND p.code = ?
                 LIMIT 1
                """, leadId.toString(), provider);
        return !rows.isEmpty();
    }

    private String snapshotJson(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "RECON_JSON",
                    "Could not serialize partner snapshot");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean payCommission(Map<String, Object> lead) {
        return !"EXTERNAL".equals(String.valueOf(lead.get("sale_channel")));
    }
}
