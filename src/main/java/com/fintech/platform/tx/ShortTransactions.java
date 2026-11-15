package com.fintech.platform.tx;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs a short database transaction so provider HTTP calls are not inside a row lock. */
@Component
public class ShortTransactions {

    private final TransactionTemplate required;

    public ShortTransactions(PlatformTransactionManager manager) {
        this.required = new TransactionTemplate(manager);
    }

    public <T> T required(Supplier<T> work) {
        return required.execute(status -> work.get());
    }

    public void required(Runnable work) {
        required.executeWithoutResult(status -> work.run());
    }
}
