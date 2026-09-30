package com.fintech.identity;

import com.fintech.recon.FdSalesReconService;
import com.fintech.reports.AdminEarningsService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminPlatformController {

    public record HubRequest(@NotBlank String name, @NotBlank String city,
                             @NotBlank String state, String notes) {}

    public record HubUpdateRequest(String name, String city, String state, String notes, String status) {}

    public record ServiceToggleRequest(@NotNull Boolean enabled) {}

    public record SettingUpdateRequest(@NotBlank String value) {}

    public record CreateDistributorRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "10-digit mobile required") String mobile,
            String email,
            @NotBlank @Size(min = 8) String password,
            @NotNull UUID hubId) {}

    public record CreateRetailerRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "10-digit mobile required") String mobile,
            String email,
            @NotBlank @Size(min = 8) String password,
            @NotNull UUID distributorId,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "6-digit pincode required") String pincode) {}

    public record CreateAdminRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "10-digit mobile required") String mobile,
            String email,
            @NotBlank @Size(min = 8) String password,
            @NotNull UUID hubId) {}

    public record StatusRequest(@NotBlank String status) {}

    public record HubAssignRequest(@NotNull UUID hubId) {}

    public record TransferRequest(@NotNull UUID distributorId) {}

    public record PasswordRequest(@NotBlank @Size(min = 8) String password) {}

    public record WalletFreezeRequest(@NotNull Boolean frozen) {}

    public record WalletAdjustRequest(
            @NotNull BigDecimal amount,
            @NotBlank String direction,
            String narration) {}

    public record KycReviewRequest(@NotBlank String decision, String reason) {}

    public record ReconRequest(@NotBlank String provider, @NotBlank String businessDate) {}

    public record ResolveRequest(@NotBlank String resolution) {}

    public record ReportRunRequest(@NotBlank String reportType, @NotBlank String title) {}

    public record AgentEkycStartRequest(@NotBlank String ovdType, @NotBlank String ovdLast4) {}

    public record AgentEkycVerifyRequest(@NotBlank String otp) {}

    private final AdminPlatformService admin;
    private final AgentEkycService agentEkyc;
    private final FdSalesReconService fdRecon;
    private final AdminEarningsService earnings;

    public AdminPlatformController(AdminPlatformService admin, AgentEkycService agentEkyc,
                                   FdSalesReconService fdRecon, AdminEarningsService earnings) {
        this.admin = admin;
        this.agentEkyc = agentEkyc;
        this.fdRecon = fdRecon;
        this.earnings = earnings;
    }

    @GetMapping("/hubs")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> hubs() {
        return ApiResponse.ok(admin.listHubs());
    }

    @GetMapping("/hub-dashboard")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> hubDashboard() {
        return ApiResponse.ok(admin.hubDashboard());
    }

    @GetMapping("/hubs/{id}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> hub(@PathVariable UUID id) {
        return ApiResponse.ok(admin.getHub(id));
    }

    @PostMapping("/hubs")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> createHub(@Valid @RequestBody HubRequest req) {
        return ApiResponse.ok(admin.createHub(req.name(), req.city(), req.state(), req.notes()));
    }

    @PatchMapping("/hubs/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> updateHub(@PathVariable UUID id, @RequestBody HubUpdateRequest req) {
        return ApiResponse.ok(admin.updateHub(id, req.name(), req.city(), req.state(), req.notes(), req.status()));
    }

    public record AssignUsersRequest(@NotEmpty List<UUID> userIds) {}

    @GetMapping("/hubs/{id}/distributors/directory")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> distributorDirectory(@PathVariable UUID id) {
        return ApiResponse.ok(admin.distributorDirectory(id));
    }

    @PostMapping("/hubs/{id}/distributors/assign")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> assignDistributors(@PathVariable UUID id,
                                                               @Valid @RequestBody AssignUsersRequest req) {
        return ApiResponse.ok(admin.assignDistributorsToHub(id, req.userIds()));
    }

    @GetMapping("/hubs/{id}/admins/directory")
    @PreAuthorize("hasAuthority('admins.manage')")
    public ApiResponse<List<Map<String, Object>>> adminDirectory(@PathVariable UUID id) {
        return ApiResponse.ok(admin.adminDirectory(id));
    }

    @PostMapping("/hubs/{id}/admins/assign")
    @PreAuthorize("hasAuthority('admins.manage')")
    public ApiResponse<Map<String, Object>> assignAdmins(@PathVariable UUID id,
                                                         @Valid @RequestBody AssignUsersRequest req) {
        return ApiResponse.ok(admin.assignAdminsToHub(id, req.userIds()));
    }

    @GetMapping("/services")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> services() {
        return ApiResponse.ok(admin.listServices());
    }

    @PatchMapping("/services/{code}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> toggleService(@PathVariable String code,
                                                          @Valid @RequestBody ServiceToggleRequest req) {
        return ApiResponse.ok(admin.setServiceEnabled(code, req.enabled()));
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> users(@RequestParam(required = false) String type) {
        return ApiResponse.ok(admin.listUsers(type));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> user(@PathVariable UUID id) {
        return ApiResponse.ok(admin.getUser(id));
    }

    @PostMapping("/users/distributors")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> createDistributor(@AuthenticationPrincipal AuthPrincipal me,
                                                              @Valid @RequestBody CreateDistributorRequest req) {
        return ApiResponse.ok(admin.createDistributor(
                req.fullName(), req.mobile(), req.email(), req.password(), req.hubId(), me.userId()));
    }

    @PostMapping("/users/retailers")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> createRetailer(@AuthenticationPrincipal AuthPrincipal me,
                                                           @Valid @RequestBody CreateRetailerRequest req) {
        return ApiResponse.ok(admin.createRetailer(
                req.fullName(), req.mobile(), req.email(), req.password(),
                req.distributorId(), req.city(), req.state(), req.pincode(), me.userId()));
    }

    @PostMapping("/users/admins")
    @PreAuthorize("hasAuthority('admins.manage')")
    public ApiResponse<Map<String, Object>> createAdmin(@AuthenticationPrincipal AuthPrincipal me,
                                                        @Valid @RequestBody CreateAdminRequest req) {
        return ApiResponse.ok(admin.createHubAdmin(
                req.fullName(), req.mobile(), req.email(), req.password(), req.hubId(), me.userId()));
    }

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> status(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return ApiResponse.ok(admin.updateStatus(id, req.status()));
    }

    @PatchMapping("/users/{id}/hub")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> hubAssign(@PathVariable UUID id, @Valid @RequestBody HubAssignRequest req) {
        return ApiResponse.ok(admin.assignHub(id, req.hubId()));
    }

    @PatchMapping("/users/{id}/distributor")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest req) {
        return ApiResponse.ok(admin.transferRetailer(id, req.distributorId()));
    }

    @PostMapping("/users/{id}/password")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> password(@PathVariable UUID id, @Valid @RequestBody PasswordRequest req) {
        return ApiResponse.ok(admin.resetPassword(id, req.password()));
    }

    @PatchMapping("/users/{id}/wallet/freeze")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> freeze(@PathVariable UUID id, @Valid @RequestBody WalletFreezeRequest req) {
        return ApiResponse.ok(admin.setWalletFrozen(id, req.frozen()));
    }

    @PostMapping("/users/{id}/wallet/adjust")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adjust(@PathVariable UUID id, @Valid @RequestBody WalletAdjustRequest req) {
        return ApiResponse.ok(admin.adjustWallet(id, req.amount(), req.direction(), req.narration()));
    }

    @PostMapping("/users/{id}/ekyc")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> startAgentEkyc(@PathVariable UUID id,
                                                           @Valid @RequestBody AgentEkycStartRequest req) {
        return ApiResponse.ok(agentEkyc.start(id, req.ovdType(), req.ovdLast4()));
    }

    @PostMapping("/users/{id}/ekyc/verify")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> verifyAgentEkyc(@PathVariable UUID id,
                                                            @Valid @RequestBody AgentEkycVerifyRequest req) {
        return ApiResponse.ok(agentEkyc.verify(id, req.otp()));
    }

    @GetMapping("/kyc")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> kyc() {
        return ApiResponse.ok(admin.listKyc());
    }

    @GetMapping("/kyc/{userId}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> kycOne(@PathVariable UUID userId) {
        return ApiResponse.ok(admin.getKyc(userId));
    }

    @PostMapping("/kyc/{userId}/review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> kycReview(@PathVariable UUID userId, @Valid @RequestBody KycReviewRequest req) {
        return ApiResponse.ok(admin.reviewKyc(userId, req.decision(), req.reason()));
    }

    @GetMapping("/earnings/filters")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> earningsFilters() {
        return ApiResponse.ok(earnings.filters());
    }

    @GetMapping("/earnings")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> earningsQuery(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String retailerId,
            @RequestParam(required = false) String distributorId,
            @RequestParam(required = false) String hubId) {
        LocalDate fromDay = from == null || from.isBlank() ? null : parseDay(from, "from");
        LocalDate toDay = to == null || to.isBlank() ? null : parseDay(to, "to");
        return ApiResponse.ok(earnings.query(fromDay, toDay, parseUuid(retailerId), parseUuid(distributorId), parseUuid(hubId)));
    }

    @GetMapping("/transactions/types")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, String>>> txnTypes() {
        return ApiResponse.ok(admin.transactionTypes());
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, Object>>> txns(
            @RequestParam String txnType,
            @RequestParam String from,
            @RequestParam String to) {
        return ApiResponse.ok(admin.listTransactions(txnType, parseDay(from, "from"), parseDay(to, "to")));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> txn(@PathVariable UUID id) {
        return ApiResponse.ok(admin.getTransaction(id));
    }

    @GetMapping("/recon")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> recon() {
        return ApiResponse.ok(admin.listRecon());
    }

    @GetMapping("/recon/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> reconOne(@PathVariable UUID id) {
        return ApiResponse.ok(admin.getRecon(id));
    }

    @PostMapping("/recon")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> createRecon(@Valid @RequestBody ReconRequest req) {
        return ApiResponse.ok(admin.createRecon(req.provider(), req.businessDate()));
    }

    @PostMapping(value = "/recon/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> reconReport(@RequestParam("reportType") String reportType,
                                                        @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(fdRecon.submitWeeklyReport(reportType, file));
    }

    @PostMapping("/recon/mismatches/{id}/resolve")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable long id, @Valid @RequestBody ResolveRequest req) {
        return ApiResponse.ok(admin.resolveMismatch(id, req.resolution()));
    }

    @DeleteMapping("/recon/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, String>> deleteRecon(@PathVariable UUID id) {
        fdRecon.deleteBatch(id);
        return ApiResponse.ok(Map.of("deleted", id.toString()));
    }

    @GetMapping("/report-runs")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> reportRuns() {
        return ApiResponse.ok(admin.listReportRuns());
    }

    @GetMapping("/report-runs/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> reportRun(@PathVariable UUID id) {
        return ApiResponse.ok(admin.getReportRun(id));
    }

    @PostMapping("/report-runs")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> createReport(@AuthenticationPrincipal AuthPrincipal me,
                                                         @Valid @RequestBody ReportRunRequest req) {
        return ApiResponse.ok(admin.createReportRun(req.reportType(), req.title(), me.userId()));
    }

    private static LocalDate parseDay(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_REQUIRED",
                    "Choose a " + field + " date");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE",
                    "Use YYYY-MM-DD for " + field);
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ID", "Invalid id");
        }
    }
}
