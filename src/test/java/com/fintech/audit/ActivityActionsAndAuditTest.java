package com.fintech.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.onboarding.InviteService;
import com.fintech.onboarding.PublicOnboardingController;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActivityActionsAndAuditTest {

    private static final String SAMPLE_USER_ID = "a11df771-6b52-4623-a962-f0eaa547e931";
    private static final String SAMPLE_DOC_ID = "450f87ea-6b91-453a-b12e-2bb85a6f229e";

    @Test
    void mapsAllKycOnboardingAgreementProviderAndAuthRoutesToPlainActions() {
        Map<String, String> postRoutes = Map.ofEntries(
                Map.entry("/api/v1/auth/login", "User Login"),
                Map.entry("/api/v1/auth/otp/send", "Login OTP Sent"),
                Map.entry("/api/v1/auth/otp/verify", "User OTP Login"),
                Map.entry("/api/v1/auth/reset/otp", "Password Reset OTP Sent"),
                Map.entry("/api/v1/auth/reset/verify", "Password Reset OTP Verified"),
                Map.entry("/api/v1/auth/reset/confirm", "Password Reset Completed"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/digilocker-documents/" + SAMPLE_DOC_ID + "/review",
                        "DigiLocker Document Reviewed"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/digilocker-documents/refetch",
                        "DigiLocker Documents Refetched"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/face-review",
                        "Selfie Face Match Reviewed"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/signatory/face-review",
                        "Signatory Face Match Reviewed"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/personal-review",
                        "Identity KYC Reviewed"),
                Map.entry("/api/v1/verification/users/" + SAMPLE_USER_ID + "/business-review",
                        "Business KYC Reviewed"),
                Map.entry("/api/v1/verification/aadhaar", "Aadhaar Verified"),
                Map.entry("/api/v1/desk/inquiries/" + SAMPLE_DOC_ID + "/invite-retailer", "Inquiry Onboarding Invite Sent"),
                Map.entry("/api/v1/verification/business/individual", "Individual Business KYC Submitted"),
                Map.entry("/api/v1/verification/business/company", "Company Business KYC Submitted"),
                Map.entry("/api/v1/verification/business/company/lookup", "Company Details Fetched"),
                Map.entry("/api/v1/reverify/otp", "Re-verification OTP Sent"),
                Map.entry("/api/v1/reverify/otp/verify", "Re-verification Mobile Verified"),
                Map.entry("/api/v1/reverify/location", "Re-verification Location Captured"),
                Map.entry("/api/v1/reverify/digilocker/start", "Re-verification DigiLocker Started"),
                Map.entry("/api/v1/reverify/digilocker/complete", "Re-verification Aadhaar Verified"),
                Map.entry("/api/v1/reverify/pan", "Re-verification PAN Verified"),
                Map.entry("/api/v1/reverify/selfie", "Re-verification Selfie Captured"),
                Map.entry("/api/v1/reverify/pin", "KYC Re-verification Completed"),
                Map.entry("/api/v1/public/onboarding/tok_abc/otp", "Onboarding OTP Sent"),
                Map.entry("/api/v1/public/onboarding/tok_abc/otp/verify", "Onboarding Mobile Verified"),
                Map.entry("/api/v1/public/onboarding/session/location", "Onboarding Location Captured"),
                Map.entry("/api/v1/public/onboarding/session/digilocker/start", "Onboarding DigiLocker Started"),
                Map.entry("/api/v1/public/onboarding/session/digilocker/complete", "Onboarding Aadhaar Verified"),
                Map.entry("/api/v1/public/onboarding/session/pan", "Onboarding PAN Verified"),
                Map.entry("/api/v1/public/onboarding/session/selfie", "Onboarding Selfie Captured"),
                Map.entry("/api/v1/public/onboarding/session/credentials", "KYC Onboarding Completed"),
                Map.entry("/api/v1/public/signatory/tok_sig/otp", "Signatory OTP Sent"),
                Map.entry("/api/v1/public/signatory/tok_sig/otp/verify", "Signatory Mobile Verified"),
                Map.entry("/api/v1/public/signatory/session/selfie", "Signatory KYC Completed"),
                Map.entry("/api/v1/agreements/users/" + SAMPLE_USER_ID + "/generate", "Agreement Draft Generated"),
                Map.entry("/api/v1/agreements/" + SAMPLE_DOC_ID + "/release", "Agreement Released for Signing"),
                Map.entry("/api/v1/agreements/" + SAMPLE_DOC_ID + "/review", "Signed Agreement Reviewed"),
                Map.entry("/api/v1/agreements/me/esign/start", "Agreement eSign Started"),
                Map.entry("/api/v1/agreements/me/esign/refresh", "Agreement eSign Status Verified"),
                Map.entry("/api/v1/wallet/banks/" + SAMPLE_DOC_ID + "/verify", "Bank Account Verified"),
                Map.entry("/api/v1/admin/users/" + SAMPLE_USER_ID + "/banks/" + SAMPLE_DOC_ID + "/verify",
                        "Bank Account Verified"),
                Map.entry("/api/v1/admin/users/" + SAMPLE_USER_ID + "/providers/emerald/onboard",
                        "Provider Onboarded"),
                Map.entry("/api/v1/admin/users/" + SAMPLE_USER_ID + "/providers/emerald/sync-bank",
                        "Provider Settlement Bank Synced"),
                Map.entry("/api/v1/admin/users/" + SAMPLE_USER_ID + "/providers/emerald/refresh",
                        "Provider Status Checked"),
                Map.entry("/api/v1/admin/users/" + SAMPLE_USER_ID + "/require-reverification",
                        "KYC Re-verification Requested"),
                Map.entry("/api/v1/me/biometric/ch_cash_1/initiate", "Biometric eKYC Started"),
                Map.entry("/api/v1/me/biometric/ch_cash_1/submit", "Biometric eKYC Submitted"),
                Map.entry("/api/v1/cashout/upi/generate-qr", "Cash Out QR Generated"),
                Map.entry("/api/v1/aeps/transact", "AePS Transaction Submitted"),
                Map.entry("/api/v1/payouts", "Settlement Payout Initiated")
        );

        postRoutes.forEach((path, expected) ->
                assertThat(ActivityActions.action("POST", path))
                        .as("Action for POST %s", path)
                        .isEqualTo(expected));

        assertThat(ActivityActions.action("PATCH",
                "/api/v1/admin/users/" + SAMPLE_USER_ID + "/providers/channels/" + SAMPLE_DOC_ID))
                .isEqualTo("Retailer Banking Channel Updated");

        // Unknown future endpoint fallback must never return raw API path
        String fallback = ActivityActions.action("POST", "/api/v1/admin/users/" + SAMPLE_USER_ID + "/kyc-score-recheck");
        assertThat(fallback).isEqualTo("KYC Score Recheck");
        assertThat(fallback).doesNotContain("api/v1").doesNotStartWith("POST ");
    }

    @Test
    void displayNormalizesPreviouslyStoredRawApiPaths() {
        assertThat(ActivityPhrases.display(
                "POST api/v1/verification/users/{id}/digilocker-documents/{id}/review",
                "POST",
                "/api/v1/verification/users/" + SAMPLE_USER_ID + "/digilocker-documents/" + SAMPLE_DOC_ID + "/review",
                "RETAILER"))
                .isEqualTo("DigiLocker Document Reviewed");

        assertThat(ActivityPhrases.display(
                "POST api/v1/verification/users/{id}/face-review", "RETAILER"))
                .isEqualTo("Selfie Face Match Reviewed");

        assertThat(ActivityPhrases.display(
                "POST api/v1/reverify/pin", null))
                .isEqualTo("KYC Re-verification Completed");
    }

    @Test
    void filterCapturesPublicOnboardingAndRedactsSensitiveFieldsWithoutBreakingPincode() throws Exception {
        ActivityAuditWriter writer = mock(ActivityAuditWriter.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID inviteId = UUID.randomUUID();
        String sessionToken = "sess_live_token_123";

        org.mockito.Mockito.when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("FROM onboarding_invites"),
                org.mockito.ArgumentMatchers.eq(InviteService.sha256(sessionToken))))
                .thenReturn(Map.of(
                        "id", inviteId,
                        "role", "RETAILER",
                        "full_name", "Nitin Jhamnani"));

        ActivityAuditFilter filter = new ActivityAuditFilter(writer, new ObjectMapper(), jdbc);

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/public/onboarding/session/credentials");
        request.setContentType("application/json");
        request.addHeader(PublicOnboardingController.SESSION_HEADER, sessionToken);
        String body = """
                {
                  "password": "Password@123",
                  "newPassword": "Password@123",
                  "pin": "123456",
                  "newPin": "123456",
                  "aadhaarNumber": "999988887777",
                  "pincode": "560102",
                  "shopName": "Nitin Store"
                }
                """;
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            // Drain body so ContentCachingRequestWrapper caches it just like Spring MVC @RequestBody
            req.getInputStream().readAllBytes();
            ((MockHttpServletResponse) res).setStatus(200);
        };

        filter.doFilter(request, response, chain);

        ArgumentCaptor<ActivityEvent> captor = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(writer).save(captor.capture());
        ActivityEvent captured = captor.getValue();

        assertThat(captured.action()).isEqualTo("KYC Onboarding Completed");
        assertThat(captured.outcome()).isEqualTo("SUCCESS");
        assertThat(captured.detail())
                .doesNotContainKeys("password", "newPassword", "pin", "newPin", "aadhaarNumber")
                .containsEntry("_inviteId", inviteId.toString())
                .containsEntry("applicantName", "Nitin Jhamnani")
                .containsEntry("role", "Retailer")
                .containsEntry("pincode", "560102")
                .containsEntry("shopName", "Nitin Store");

        assertThat(List.of(
                ActivityActions.uuidAfterSegment(
                        "/api/v1/verification/users/" + SAMPLE_USER_ID + "/digilocker-documents/" + SAMPLE_DOC_ID + "/review",
                        "digilocker-documents")))
                .containsExactly(UUID.fromString(SAMPLE_DOC_ID));
    }
}

