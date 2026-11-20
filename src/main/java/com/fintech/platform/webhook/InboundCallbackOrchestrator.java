package com.fintech.platform.webhook;

import com.fintech.cashout.CashoutService;
import com.fintech.iserveu.IserveuCallbackAdapter;
import com.fintech.payout.PayoutService;
import com.fintech.provider.canonical.ProviderModels;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound callbacks: verify the provider, cap the body, dedupe in {@code webhook_inbox},
 * then apply the settlement through the domain service.
 */
@Service
public class InboundCallbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InboundCallbackOrchestrator.class);

    private final WebhookInbox inbox;
    private final IserveuCallbackAdapter iserveu;
    private final CashoutService cashout;
    private final PayoutService payout;

    public InboundCallbackOrchestrator(
            WebhookInbox inbox,
            IserveuCallbackAdapter iserveu,
            CashoutService cashout,
            PayoutService payout) {
        this.inbox = inbox;
        this.iserveu = iserveu;
        this.cashout = cashout;
        this.payout = payout;
    }

    public boolean iserveuAuthorized(String passKey) {
        return iserveu.passKeyMatches(passKey);
    }

    @Transactional
    public UUID acceptIserveu(String rawBody) {
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > WebhookInbox.MAX_BODY_BYTES) {
            log.warn("ISERVEU_CASHPOINT_WEBHOOK rejected reason=body_too_large");
            return null;
        }
        ProviderModels.SettlementEvent event = IserveuCallbackAdapter.interpret(readQuiet(rawBody));
        String status = event.status() == null ? "" : event.status();
        String idPart = !event.providerTxnId().isBlank() ? event.providerTxnId() : event.externalRef();
        if (idPart.isBlank() || status.isBlank()) {
            log.info("ISERVEU_CASHPOINT_WEBHOOK ignored reason=incomplete");
            return null;
        }
        String dedupe = idPart + ":" + status;
        Optional<Long> stored = inbox.store("ISERVEU", dedupe, rawBody);
        if (stored.isEmpty()) {
            log.info("ISERVEU_CASHPOINT_WEBHOOK duplicate event={}", dedupe);
            return null;
        }
        UUID txnId = null;
        if (!"PENDING".equals(status)) {
            txnId = cashout.finalizeFromCallback(
                    event.externalRef(),
                    event.providerTxnId(),
                    status,
                    event.message(),
                    event.utr(),
                    event.payerVpa(),
                    event.payerName());
        }
        inbox.markProcessed(stored.get(), txnId);
        log.info("ISERVEU_CASHPOINT_WEBHOOK accepted status={} matched={}", status, txnId != null);
        return txnId;
    }

    @Transactional
    public UUID acceptIserveuPayout(String rawBody) {
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > WebhookInbox.MAX_BODY_BYTES) {
            log.warn("ISERVEU_PAYOUT_WEBHOOK rejected reason=body_too_large");
            return null;
        }
        ProviderModels.SettlementEvent event = IserveuCallbackAdapter.interpret(readQuiet(rawBody));
        String status = event.status() == null ? "" : event.status();
        String idPart = !event.providerTxnId().isBlank() ? event.providerTxnId() : event.externalRef();
        if (idPart.isBlank() || status.isBlank()) {
            log.info("ISERVEU_PAYOUT_WEBHOOK ignored reason=incomplete");
            return null;
        }
        String dedupe = "PAYOUT:" + idPart + ":" + status;
        Optional<Long> stored = inbox.store("ISERVEU", dedupe, rawBody);
        if (stored.isEmpty()) {
            log.info("ISERVEU_PAYOUT_WEBHOOK duplicate event={}", dedupe);
            return null;
        }
        UUID txnId = null;
        if (!"PENDING".equals(status)) {
            txnId = payout.finalizeFromCallback(
                    event.externalRef(),
                    event.providerTxnId(),
                    status,
                    event.message(),
                    event.utr());
        }
        inbox.markProcessed(stored.get(), txnId);
        log.info("ISERVEU_PAYOUT_WEBHOOK accepted status={} matched={}", status, txnId != null);
        return txnId;
    }

    private static com.fasterxml.jackson.databind.JsonNode readQuiet(String rawBody) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception e) {
            return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        }
    }
}
