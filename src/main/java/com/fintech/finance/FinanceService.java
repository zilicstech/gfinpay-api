package com.fintech.finance;

import com.fintech.identity.AdminAccess;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.persistence.finance.FinanceRequestRepository;
import com.fintech.persistence.identity.UserRepository;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PageWindow;
import com.fintech.storage.SpacesStorage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FinanceService {

    public static final String ACTIVATION = "ACTIVATION";
    public static final String WALLET_TOPUP = "WALLET_TOPUP";
    public static final String EARNINGS_WITHDRAW = "EARNINGS_WITHDRAW";
    public static final String PENDING = "PENDING";
    public static final String RECEIVED = "RECEIVED";
    public static final String REJECTED = "REJECTED";

    private static final Logger log = LoggerFactory.getLogger(FinanceService.class);
    private static final Set<String> TRANSFER_MODES = Set.of("IMPS", "NEFT", "RTGS", "UPI", "QR");

    private static final String SELECT = """
            SELECT f.id, f.kind, f.retailer_user_id, f.requested_by, f.amount, f.utr,
                   f.proof_key, f.proof_filename, f.proof_content_type, f.remarks, f.status,
                   f.auditor_user_id, f.auditor_notes, f.reviewed_at, f.wallet_txn_id,
                   f.created_at, f.updated_at, f.transfer_mode,
                   f.collection_account_id, f.payout_account_id,
                   pb.bank_name AS collection_bank_name, pb.account_name AS collection_account_name,
                   pb.account_number AS collection_account_number, pb.ifsc AS collection_ifsc,
                   pb.upi_id AS collection_upi,
                   rb.bank_name AS payout_bank_name, rb.account_name AS payout_account_name,
                   rb.account_number AS payout_account_number, rb.ifsc AS payout_ifsc,
                   r.full_name AS retailer_name, r.mobile AS retailer_mobile, r.code AS retailer_code,
                   r.status::text AS retailer_status, r.parent_id AS distributor_id, r.hub_id,
                   d.full_name AS distributor_name,
                   req.full_name AS requested_by_name, req.user_type::text AS requested_by_type,
                   aud.full_name AS auditor_name
              FROM finance_requests f
              JOIN users r ON r.id = f.retailer_user_id
              LEFT JOIN users d ON d.id = r.parent_id
              JOIN users req ON req.id = f.requested_by
              LEFT JOIN users aud ON aud.id = f.auditor_user_id
              LEFT JOIN platform_bank_accounts pb ON pb.id = f.collection_account_id
              LEFT JOIN retailer_bank_accounts rb ON rb.id = f.payout_account_id
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final SpacesStorage spaces;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;

    private final FinanceRequestRepository financeRequests;
    private final UserRepository users;

    public FinanceService(JdbcTemplate jdbc, AdminAccess access, SpacesStorage spaces,
                          WalletService walletService, LedgerService ledgerService,
                          TransactionService transactionService,
                          FinanceRequestRepository financeRequests, UserRepository users) {
        this.jdbc = jdbc;
        this.access = access;
        this.spaces = spaces;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.financeRequests = financeRequests;
        this.users = users;
    }

    @Transactional
    public Map<String, Object> submit(AuthPrincipal me, String kind, UUID retailerId, BigDecimal amount,
                                      String utr, String remarks, MultipartFile proof) {
        return submit(me, kind, retailerId, amount, utr, remarks, proof, null, null);
    }

    @Transactional
    public Map<String, Object> submit(AuthPrincipal me, String kind, UUID retailerId, BigDecimal amount,
                                      String utr, String remarks, MultipartFile proof,
                                      UUID collectionAccountId, String transferMode) {
        requireKind(kind);
        UUID target = resolveRetailer(me, kind, retailerId);
        assertCanSubmit(me, target);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        String ref = utr == null ? "" : utr.trim();
        if (ref.length() < 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UTR",
                    "Enter the UTR / UPI reference (at least 6 characters)");
        }
        Map<String, Object> retailer = retailerRow(target);
        String retailerStatus = String.valueOf(retailer.get("status"));
        if (ACTIVATION.equals(kind)) {
            if ("ACTIVE".equals(retailerStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_ACTIVE",
                        "This retailer is already activated");
            }
            if ("SUBMITTED".equals(retailerStatus) || "APPROVED".equals(retailerStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_IN_PROGRESS",
                        "An activation request is already with the auditor, or it has been approved");
            }
            if (!"PENDING".equals(retailerStatus) && !"REJECTED".equals(retailerStatus)
                    && !"PENDING_ACTIVATION".equals(retailerStatus) && !"PENDING_KYC".equals(retailerStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RETAILER_STATUS",
                        "Activation can be requested when the retailer is pending or the last request was rejected");
            }
        } else if (!"ACTIVE".equals(retailerStatus)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_NOT_ACTIVE",
                    "Wallet top-up is available after the retailer is activated");
        }
        if (ACTIVATION.equals(kind) && hasPending(target, kind)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REQUEST_PENDING",
                    "An activation request is already pending review");
        }
        UUID collectionId = null;
        String mode = null;
        if (WALLET_TOPUP.equals(kind)) {
            collectionId = requireCollectionAccount(collectionAccountId);
            mode = transferMode(transferMode);
        }
        SpacesStorage.StoredObject stored = spaces.upload(kind, proof);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO finance_requests
                        (id, kind, retailer_user_id, requested_by, amount, utr,
                         proof_key, proof_filename, proof_content_type, remarks, status,
                         collection_account_id, transfer_mode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, id, kind, target, me.userId(), amount, ref,
                    stored.key(), stored.filename(), stored.contentType(), cleanRemarks(remarks),
                    collectionId, mode);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REQUEST_PENDING",
                    "A request of this type is already pending review");
        }
        if (ACTIVATION.equals(kind)) {
            users.updateStatus(target, "SUBMITTED");
        }
        log.info("FINANCE_SUBMIT id={} kind={} retailer={} by={}", id, kind, target, me.userId());
        return get(me, id);
    }

    @Transactional
    public Map<String, Object> submitEarningsWithdrawal(AuthPrincipal me, BigDecimal amount, String remarks,
                                                        UUID payoutAccountId) {
        if (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer or distributor can request a rewards withdrawal");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        Map<String, Object> party = jdbc.queryForMap(
                "SELECT status::text AS status FROM users WHERE id = ?", me.userId());
        if (!"ACTIVE".equals(String.valueOf(party.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USER_NOT_ACTIVE",
                    "Rewards can be withdrawn after the account is active");
        }
        WalletService.WalletInfo wallet = walletService.getRewards(me.userId());
        if (wallet.available().compareTo(amount) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Available balance is less than the withdrawal");
        }
        UUID payoutId = requirePayoutAccount(me.userId(), payoutAccountId);
        UUID id = UUID.randomUUID();
        String ref = "WD" + id.toString().replace("-", "").substring(0, 12);
        try {
            jdbc.update("""
                    INSERT INTO finance_requests
                        (id, kind, retailer_user_id, requested_by, amount, utr,
                         proof_key, remarks, status, payout_account_id)
                    VALUES (?, 'EARNINGS_WITHDRAW', ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, id, me.userId(), me.userId(), amount, ref,
                    "earnings-withdraw/" + id, cleanRemarks(remarks), payoutId);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REQUEST_PENDING",
                    "A withdrawal request is already pending review");
        }
        log.info("FINANCE_SUBMIT id={} kind=EARNINGS_WITHDRAW retailer={}", id, me.userId());
        return get(me, id);
    }

    public List<Map<String, Object>> list(AuthPrincipal me, String kind, String status, UUID retailerId, UUID auditorId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        scope(me, sql, args);
        if (kind != null && !kind.isBlank()) {
            requireKind(kind);
            sql.append(" AND f.kind = ? ");
            args.add(kind);
        }
        if (status != null && !status.isBlank()) {
            requireStatus(status);
            sql.append(" AND f.status = ? ");
            args.add(status);
        }
        if (retailerId != null) {
            assertCanViewRetailer(me, retailerId);
            sql.append(" AND f.retailer_user_id = ? ");
            args.add(retailerId);
        }
        if (auditorId != null) {
            if (!me.superAdminOrPlatformAdmin()) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed to view this audit history");
            }
            sql.append(" AND f.auditor_user_id = ? AND f.status IN ('RECEIVED', 'REJECTED') ");
            args.add(auditorId);
        }
        sql.append(auditorId == null
                ? " ORDER BY f.created_at DESC LIMIT 500 "
                : " ORDER BY f.reviewed_at DESC NULLS LAST, f.created_at DESC LIMIT 500 ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> get(AuthPrincipal me, UUID id) {
        Map<String, Object> row = one(id);
        assertCanViewRetailer(me, (UUID) row.get("retailer_user_id"));
        return row;
    }

    public Map<String, Object> proofUrl(AuthPrincipal me, UUID id) {
        Map<String, Object> row = get(me, id);
        String key = (String) row.get("proof_key");
        if (key == null || key.startsWith("earnings-withdraw/") || row.get("proof_filename") == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "PROOF_NOT_FOUND", "This request has no uploaded proof");
        }
        return Map.of(
                "url", spaces.presignedGet(key),
                "filename", row.get("proof_filename"),
                "contentType", row.get("proof_content_type") == null ? "" : row.get("proof_content_type"));
    }

    @Transactional
    public Map<String, Object> receive(AuthPrincipal me, UUID id, String notes) {
        requireReviewer(me);
        Map<String, Object> row = one(id);
        if (!PENDING.equals(String.valueOf(row.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PENDING",
                    "Only a pending request can be marked received");
        }
        String kind = String.valueOf(row.get("kind"));
        if (EARNINGS_WITHDRAW.equals(kind)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_TRANSFER_REQUIRED",
                    "Mark a withdrawal as transferred, with the UTR, payment proof, and source bank");
        }
        UUID retailerId = (UUID) row.get("retailer_user_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        UUID txnId = null;
        if (WALLET_TOPUP.equals(kind)) {
            txnId = creditWallet(retailerId, amount, "Wallet top-up UTR " + row.get("utr"));
        }
        financeRequests.markReceived(id, me.userId(), blankToNull(notes), txnId);
        if (ACTIVATION.equals(kind)) {
            users.updateStatus(retailerId, "APPROVED");
        }
        log.info("FINANCE_RECEIVED id={} kind={} retailer={} txnId={}", id, kind, retailerId, txnId);
        return one(id);
    }

    @Transactional
    public Map<String, Object> reject(AuthPrincipal me, UUID id, String notes) {
        requireReviewer(me);
        Map<String, Object> row = one(id);
        if (!PENDING.equals(String.valueOf(row.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PENDING",
                    "Only a pending request can be rejected");
        }
        financeRequests.markRejected(id, me.userId(), blankToNull(notes));
        if (ACTIVATION.equals(String.valueOf(row.get("kind")))) {
            users.updateStatus((UUID) row.get("retailer_user_id"), "REJECTED");
        }
        log.info("FINANCE_REJECTED id={} by={}", id, me.userId());
        return one(id);
    }

    @Transactional
    public Map<String, Object> transferWithdrawal(AuthPrincipal me, UUID id, String utr, String notes,
                                                  UUID collectionAccountId, MultipartFile proof) {
        requireReviewer(me);
        Map<String, Object> row = one(id);
        if (!PENDING.equals(String.valueOf(row.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PENDING",
                    "Only a pending withdrawal can be marked transferred");
        }
        if (!EARNINGS_WITHDRAW.equals(String.valueOf(row.get("kind")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_WITHDRAWAL",
                    "Only a withdrawal can be marked transferred");
        }
        String ref = utr == null ? "" : utr.trim();
        if (ref.length() < 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UTR",
                    "Enter the UTR / UPI reference (at least 6 characters)");
        }
        UUID bankId = requireCollectionAccount(collectionAccountId,
                "Choose the bank you transferred this withdrawal from");
        SpacesStorage.StoredObject stored = spaces.upload("withdrawal_transfer", proof);
        UUID retailerId = (UUID) row.get("retailer_user_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        UUID txnId = debitRewards(retailerId, amount, "Rewards withdrawal UTR " + ref);
        jdbc.update("""
                UPDATE finance_requests
                   SET utr = ?, proof_key = ?, proof_filename = ?, proof_content_type = ?,
                       collection_account_id = ?, updated_at = now()
                 WHERE id = ?
                """, ref, stored.key(), stored.filename(), stored.contentType(), bankId, id);
        financeRequests.markReceived(id, me.userId(), blankToNull(notes), txnId);
        log.info("FINANCE_TRANSFERRED id={} retailer={} bank={} txnId={}", id, retailerId, bankId, txnId);
        return one(id);
    }

    @Transactional
    public void activateRetailer(AuthPrincipal me, UUID retailerId) {
        assertCanActivate(me, retailerId);
        Map<String, Object> retailer = retailerRow(retailerId);
        String retailerStatus = String.valueOf(retailer.get("status"));
        if ("ACTIVE".equals(retailerStatus)) {
            return;
        }
        if (!"APPROVED".equals(retailerStatus)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_NOT_APPROVED",
                    "The auditor must approve the activation request before this retailer can go live");
        }
        if (!hasReceivedActivation(retailerId)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_PAYMENT_REQUIRED",
                    "Mark the activation payment as received before activating this retailer");
        }
        int n = users.activateRetailer(retailerId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RETAILER_NOT_FOUND", "Retailer does not exist");
        }
        log.info("RETAILER_ACTIVATED retailer={} by={}", retailerId, me.userId());
    }

    public boolean hasReceivedActivation(UUID retailerId) {
        return financeRequests.countReceivedActivations(retailerId) > 0;
    }

    public Map<String, Object> activationSummary(UUID retailerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + """
                 WHERE f.retailer_user_id = ? AND f.kind = 'ACTIVATION'
                 ORDER BY f.created_at DESC LIMIT 1
                """, retailerId);
        String retailerStatus = jdbc.queryForObject(
                "SELECT status::text FROM users WHERE id = ?", String.class, retailerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canActivate", "APPROVED".equals(retailerStatus) && hasReceivedActivation(retailerId));
        out.put("latest", rows.isEmpty() ? null : rows.get(0));
        return out;
    }

    public List<Map<String, Object>> listRetailers() {
        return listRetailers(PageWindow.DEFAULT_LIMIT, 0);
    }

    public List<Map<String, Object>> listRetailers(int limit, int offset) {
        return jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.code, u.status::text AS status, u.created_at,
                       u.parent_id, p.full_name AS parent_name, u.hub_id, h.name AS hub_name,
                       w.available_balance, w.hold_balance, w.status AS wallet_status
                  FROM users u
                  LEFT JOIN users p ON p.id = u.parent_id
                  LEFT JOIN hubs h ON h.id = u.hub_id
                  LEFT JOIN wallets w ON w.user_id = u.id AND w.wallet_kind = 'MAIN'
                 WHERE u.user_type = 'RETAILER'
                 ORDER BY u.full_name
                 LIMIT ? OFFSET ?
                """, PageWindow.limit(limit), PageWindow.offset(offset));
    }

    public Map<String, Object> getRetailer(UUID retailerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.email, u.code, u.status::text AS status, u.created_at,
                       u.parent_id, p.full_name AS parent_name, u.hub_id, h.name AS hub_name, h.code AS hub_code
                  FROM users u
                  LEFT JOIN users p ON p.id = u.parent_id
                  LEFT JOIN hubs h ON h.id = u.hub_id
                 WHERE u.id = ? AND u.user_type = 'RETAILER'
                """, retailerId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RETAILER_NOT_FOUND", "Retailer does not exist");
        }
        Map<String, Object> user = new LinkedHashMap<>(rows.get(0));
        try {
            WalletService.WalletInfo w = walletService.getByUser(retailerId);
            user.put("wallet", Map.of(
                    "walletId", w.walletId(),
                    "availableBalance", w.available(),
                    "holdBalance", w.hold(),
                    "status", w.status()));
        } catch (ApiException e) {
            user.put("wallet", null);
        }
        user.put("activation", activationSummary(retailerId));
        return user;
    }

    @Transactional
    public Map<String, Object> adjustRetailerWallet(AuthPrincipal me, UUID retailerId, BigDecimal amount,
                                                    String direction, String narration) {
        requireAdjuster(me);
        Map<String, Object> retailer = retailerRow(retailerId);
        if (!"RETAILER".equals(String.valueOf(retailer.get("user_type")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_RETAILER",
                    "Auditors can only adjust retailer wallets");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        if (!"CREDIT".equals(direction) && !"DEBIT".equals(direction)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECTION",
                    "Direction must be CREDIT or DEBIT");
        }
        String note = (narration == null || narration.isBlank())
                ? "Auditor wallet adjustment" : narration.trim();
        if ("CREDIT".equals(direction)) {
            creditWallet(retailerId, amount, note);
        } else {
            debitWallet(retailerId, amount, note);
        }
        log.info("AUDITOR_WALLET_ADJUST retailer={} dir={} amount={} by={}", retailerId, direction, amount, me.userId());
        return getRetailer(retailerId);
    }

    private UUID creditWallet(UUID userId, BigDecimal amount, String note) {
        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        UUID txnId = transactionService.create("ADJUSTMENT", userId, wallet.walletId(), amount, BigDecimal.ZERO,
                null, null, "AUDITOR", null);
        ledgerService.post(txnId, wallet.walletId(), "CREDIT", amount, note);
        walletService.adjustCache(wallet.walletId(), amount, BigDecimal.ZERO);
        transactionService.transition(txnId, "SUCCESS", null, null);
        return txnId;
    }

    private UUID debitWallet(UUID userId, BigDecimal amount, String note) {
        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        if (wallet.available().compareTo(amount) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Available float is less than the debit");
        }
        UUID txnId = transactionService.create("ADJUSTMENT", userId, wallet.walletId(), amount, BigDecimal.ZERO,
                null, null, "AUDITOR", null);
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, note);
        walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        transactionService.transition(txnId, "SUCCESS", null, null);
        return txnId;
    }

    private UUID debitRewards(UUID userId, BigDecimal amount, String note) {
        WalletService.WalletInfo wallet = walletService.lockRewards(userId);
        if (wallet.available().compareTo(amount) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Rewards balance is less than the withdrawal");
        }
        UUID txnId = transactionService.create("EARNINGS_WITHDRAW", userId, wallet.walletId(), amount, BigDecimal.ZERO,
                null, null, "REWARDS", null);
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, note);
        walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        transactionService.transition(txnId, "SUCCESS", null, null);
        return txnId;
    }

    private UUID resolveRetailer(AuthPrincipal me, String kind, UUID retailerId) {
        if ("RETAILER".equals(me.userType())) {
            if (retailerId != null && !retailerId.equals(me.userId())) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only submit for your own outlet");
            }
            return me.userId();
        }
        if (retailerId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_REQUIRED",
                    "Choose the retailer for this " + (ACTIVATION.equals(kind) ? "activation" : "wallet") + " request");
        }
        return retailerId;
    }

    private void assertCanSubmit(AuthPrincipal me, UUID retailerId) {
        if ("RETAILER".equals(me.userType())) {
            return;
        }
        if (me.platformStaff() || "MASTER_DISTRIBUTOR".equals(me.userType())) {
            access.assertNetworkUser(retailerId);
            retailerRow(retailerId);
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed to submit this request");
    }

    private void assertCanActivate(AuthPrincipal me, UUID retailerId) {
        if (!(me.platformStaff() || "MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only an admin or the retailer's distributor can activate");
        }
        access.assertNetworkUser(retailerId);
        retailerRow(retailerId);
    }

    private void assertCanViewRetailer(AuthPrincipal me, UUID retailerId) {
        if (me.auditor() || me.superAdminOrPlatformAdmin() || me.userId().equals(retailerId)) {
            return;
        }
        if ("RETAILER".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        access.assertNetworkUser(retailerId);
    }

    private void scope(AuthPrincipal me, StringBuilder sql, List<Object> args) {
        if (me.auditor() || me.superAdminOrPlatformAdmin()) {
            return;
        }
        if (me.hubAdmin()) {
            access.appendHubFilter(sql, args, "r.hub_id");
            return;
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            sql.append(" AND (r.parent_id = ? OR f.retailer_user_id = ?) ");
            args.add(me.userId());
            args.add(me.userId());
            return;
        }
        if ("RETAILER".equals(me.userType())) {
            sql.append(" AND f.retailer_user_id = ? ");
            args.add(me.userId());
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
    }

    private void requireReviewer(AuthPrincipal me) {
        if (!me.auditor() && !me.superAdminOrPlatformAdmin()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an auditor can review payments");
        }
    }

    private void requireAdjuster(AuthPrincipal me) {
        if (!me.auditor()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an auditor can use this adjustment");
        }
    }

    private boolean hasPending(UUID retailerId, String kind) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM finance_requests
                 WHERE retailer_user_id = ? AND kind = ? AND status = 'PENDING'
                """, Integer.class, retailerId, kind);
        return n != null && n > 0;
    }

    private Map<String, Object> retailerRow(UUID retailerId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT id, user_type::text AS user_type, status::text AS status, parent_id, hub_id
                      FROM users WHERE id = ?
                    """, retailerId);
            if (!"RETAILER".equals(String.valueOf(row.get("user_type")))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_RETAILER",
                        "Requests can only be raised for a retailer");
            }
            return row;
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RETAILER_NOT_FOUND", "Retailer does not exist");
        }
    }

    private Map<String, Object> one(UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE f.id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Finance request not found");
        }
    }

    private static void requireKind(String kind) {
        if (!ACTIVATION.equals(kind) && !WALLET_TOPUP.equals(kind) && !EARNINGS_WITHDRAW.equals(kind)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KIND",
                    "Kind must be ACTIVATION, WALLET_TOPUP, or EARNINGS_WITHDRAW");
        }
    }

    private static void requireStatus(String status) {
        if (!PENDING.equals(status) && !RECEIVED.equals(status) && !REJECTED.equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "Status must be PENDING, RECEIVED, or REJECTED");
        }
    }

    private static String cleanRemarks(String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > 500) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REMARKS_TOO_LONG",
                    "Remarks must be 500 characters or fewer");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private UUID requireCollectionAccount(UUID id) {
        return requireCollectionAccount(id, "Choose the bank account you transferred to");
    }

    private UUID requireCollectionAccount(UUID id, String missingMessage) {
        if (id == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_REQUIRED", missingMessage);
        }
        try {
            Boolean active = jdbc.queryForObject(
                    "SELECT active FROM platform_bank_accounts WHERE id = ?", Boolean.class, id);
            if (!Boolean.TRUE.equals(active)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_INACTIVE",
                        "This collection account is not active");
            }
            return id;
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "Collection account does not exist");
        }
    }

    private UUID requirePayoutAccount(UUID userId, UUID id) {
        if (id == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_REQUIRED",
                    "Choose the bank account that should receive this withdrawal");
        }
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT user_id, active FROM retailer_bank_accounts WHERE id = ?", id);
            if (!userId.equals(row.get("user_id"))) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This bank account is not yours");
            }
            if (!Boolean.TRUE.equals(row.get("active"))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_INACTIVE",
                        "This bank account is not active");
            }
            return id;
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "Bank account does not exist");
        }
    }

    private static String transferMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TRANSFER_MODES.contains(mode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MODE",
                    "Choose IMPS, NEFT, RTGS, UPI, or QR code");
        }
        return mode;
    }
}
