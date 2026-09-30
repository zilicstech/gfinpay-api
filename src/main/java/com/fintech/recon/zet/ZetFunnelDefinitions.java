package com.fintech.recon.zet;

import com.fintech.recon.FdPartnerLifecycle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZetFunnelDefinitions {

    private static final List<ZetSheetFunnel> SHEETS = List.of(
            new ZetSheetFunnel("SBM", "SBM", List.of(
                    new ZetFunnelStage("ph_no_entered_time", "PHONE_ENTERED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("ph_no_verified_time", "PHONE_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("pan_entered_time", "PAN_ENTERED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("pan_verified_time", "PAN_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("bank_acc_verified_time", "BANK_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("fd_payment_initiated_time", "FD_PAYMENT_STARTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("fd_payment_failed_time", "FD_PAYMENT_FAILED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("fd_payment_success_time", "FD_PAID", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("email_entered_time", "EMAIL_ENTERED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("email_verified_time", "EMAIL_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("aadhaar_entered_time", "AADHAAR_ENTERED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("aadhaar_verified_time", "AADHAAR_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("application_submitted_time", "APPLICATION_SUBMITTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("vkyc_skipped_time", "VKYC_SKIPPED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("vkyc_started_time", "VKYC_STARTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("vkyc_location_mismatch_time", "VKYC_LOCATION_MISMATCH", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("vkyc_failed_time", "VKYC_FAILED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("fd_booked_time", "FD_BOOKED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("virtual_card_activated_time", "ACTIVATED", FdPartnerLifecycle.ACTIVATED),
                    new ZetFunnelStage("physical_card_activated_time", "PHYSICAL_CARD_ACTIVATED", FdPartnerLifecycle.ACTIVATED))),
            new ZetSheetFunnel("IOB", "IOB", List.of(
                    new ZetFunnelStage("iob_pan_verified_time", "PAN_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_bank_account_verified_time", "BANK_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_fd_payment_initiated_time", "FD_PAYMENT_STARTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_fd_payment_successful_time", "FD_PAID", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_aadhaar_verified_time", "AADHAAR_VERIFIED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_personal_details_time", "PERSONAL_DETAILS", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_address_details_time", "ADDRESS_DETAILS", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_fd_nominee_time", "FD_NOMINEE", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_card_details_time", "CARD_DETAILS", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_vkyc_started_time", "VKYC_STARTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_vkyc_completed_time", "VKYC_COMPLETED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_cif_submitted_time", "CIF_SUBMITTED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_fd_booked_time", "FD_BOOKED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_card_created_time", "CARD_CREATED", FdPartnerLifecycle.IN_PROGRESS),
                    new ZetFunnelStage("iob_card_activated_time", "ACTIVATED", FdPartnerLifecycle.ACTIVATED))));

    private ZetFunnelDefinitions() {}

    public static List<ZetSheetFunnel> sheets() {
        return SHEETS;
    }

    public static ZetSheetFunnel forSheetName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (ZetSheetFunnel sheet : SHEETS) {
            if (sheet.sheetName().equals(normalized)) {
                return sheet;
            }
        }
        return null;
    }

    public static Map<String, Integer> headerIndex(String[] headers) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] != null && !headers[i].isBlank()) {
                out.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return out;
    }

    public static boolean requiresActivationHeader(ZetSheetFunnel sheet) {
        return sheet.stages().stream().anyMatch(s -> "ACTIVATED".equals(s.partnerStatus()));
    }
}
