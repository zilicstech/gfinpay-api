package com.fintech.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.fdcards.FdAdminService;
import com.fintech.identity.AdminPlatformService;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.ServiceSettlement;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Import(QueryCountConfiguration.class)
class PersistenceCharacterizationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATASOURCE_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATASOURCE_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATASOURCE_PASSWORD", ""));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminPlatformService admin;
    @Autowired LedgerService ledger;
    @Autowired WalletService wallets;
    @Autowired TransactionService transactions;
    @Autowired ServiceSettlement settlement;
    @Autowired FdAdminService fdAdmin;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void signInAsSuperAdmin() {
        tx = new TransactionTemplate(transactionManager);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        AuthPrincipal principal = new AuthPrincipal(id, "Test Super Admin", "SUPER_ADMIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void perfIndexesExist() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*)::int FROM pg_indexes
                 WHERE indexname IN ('idx_txn_dmt_sender', 'idx_customers_retailer', 'idx_dmt_senders_registered_by')
                """, Integer.class);
        assertEquals(3, n);
    }

    @Test
    @Transactional
    void hubDashboardTotalsMatchSeededNetwork() {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        UUID hubId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        UUID retailerId = UUID.randomUUID();
        String tag = hubId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, status, created_by)
                VALUES (?, ?, 'Test Hub', 'Bengaluru', 'KA', 'ACTIVE', ?)
                """, hubId, "H" + tag, adminId);
        jdbc.update("""
                INSERT INTO users (id, user_type, parent_id, hub_id, full_name, mobile, password_hash, status, code)
                VALUES (?, 'MASTER_DISTRIBUTOR', ?, ?, 'Test Distributor', ?, 'x', 'ACTIVE', ?)
                """, distributorId, adminId, hubId, "81" + tag.substring(0, 8), "D" + tag);
        jdbc.update("""
                INSERT INTO users (id, user_type, parent_id, hub_id, full_name, mobile, password_hash, status, code)
                VALUES (?, 'RETAILER', ?, ?, 'Test Retailer', ?, 'x', 'ACTIVE', ?)
                """, retailerId, distributorId, hubId, "82" + tag.substring(0, 8), "R" + tag);
        wallets.createForUser(distributorId);
        wallets.createForUser(retailerId);

        Map<String, Object> dashboard = admin.hubDashboard();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) dashboard.get("totals");
        assertTrue(((Number) totals.get("hubs")).intValue() >= 1);
        assertTrue(((Number) totals.get("distributors")).longValue() >= 1);
        assertTrue(((Number) totals.get("retailers")).longValue() >= 1);
        assertTrue(dashboard.containsKey("hubs"));
    }

    @Test
    void providerListDoesNotQueryPerProvider() {
        CountingDataSource.reset();
        List<Map<String, Object>> providers = fdAdmin.listProviders();
        assertTrue(providers.size() >= 3);
        CountingDataSource.assertAtMost(4);
    }

    @Test
    @Transactional
    void moneyMovementsStayBalanced() {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        UUID hubId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        UUID retailerId = UUID.randomUUID();
        String tag = hubId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, created_by)
                VALUES (?, ?, 'Balance Hub', 'Mumbai', 'MH', ?)
                """, hubId, "B" + tag, adminId);
        jdbc.update("""
                INSERT INTO users (id, user_type, parent_id, hub_id, full_name, mobile, password_hash, status, code)
                VALUES (?, 'MASTER_DISTRIBUTOR', ?, ?, 'Balance Distributor', ?, 'x', 'ACTIVE', ?)
                """, distributorId, adminId, hubId, "83" + tag.substring(0, 8), "BD" + tag);
        jdbc.update("""
                INSERT INTO users (id, user_type, parent_id, hub_id, full_name, mobile, password_hash, status, code)
                VALUES (?, 'RETAILER', ?, ?, 'Balance Retailer', ?, 'x', 'ACTIVE', ?)
                """, retailerId, distributorId, hubId, "84" + tag.substring(0, 8), "BR" + tag);
        wallets.createForUser(distributorId);
        UUID retailerWallet = wallets.createForUser(retailerId);
        WalletService.WalletInfo wallet = wallets.getByUser(retailerId);

        tx.executeWithoutResult(status -> {
            UUID credit = transactions.create("ADJUSTMENT", retailerId, retailerWallet, new BigDecimal("5000"),
                    BigDecimal.ZERO, null, null, "TEST", null);
            ledger.post(credit, wallet.walletId(), "CREDIT", new BigDecimal("5000"), "seed");
            wallets.adjustCache(retailerWallet, new BigDecimal("5000"), BigDecimal.ZERO);
            transactions.transition(credit, "SUCCESS", null, null);

            settlement.debitAndSettle(retailerId, "BBPS", new BigDecimal("100"), "TEST", "bill-" + tag, null);
            settlement.debitAndSettle(retailerId, "DMT", new BigDecimal("80"), "TEST", "dmt-" + tag, null);

            UUID cashout = transactions.create("CASHOUT_UPI", retailerId, retailerWallet, new BigDecimal("50"),
                    BigDecimal.ZERO, null, null, "TEST", null);
            WalletService.WalletInfo locked = wallets.lockByUser(retailerId);
            ledger.post(cashout, locked.walletId(), "CREDIT", new BigDecimal("50"), "cashout");
            wallets.adjustCache(locked.walletId(), new BigDecimal("50"), BigDecimal.ZERO);
            transactions.transition(cashout, "SUCCESS", "cashout-" + tag, null);
        });

        WalletService.WalletInfo after = wallets.getByUser(retailerId);
        assertEquals(0, new BigDecimal("4864.72").compareTo(after.available()));
    }
}
