package com.exgpu.exgpu.config;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a callback after the current transaction commits — or immediately, inline, if there is
 * no active transaction.
 *
 * <p>Used wherever an in-memory side effect (returning capacity to
 * {@link com.exgpu.exgpu.engine.MatchingEngine}, e.g.) must never be applied ahead of the DB
 * write it depends on. If the surrounding transaction rolls back, the callback simply never
 * runs — there is nothing to undo.
 *
 * <p>The inline fallback is what makes this unit-testable without a real transaction manager:
 * a plain JUnit test can call {@link TransactionSynchronizationManager#initSynchronization()}
 * to exercise the "after commit" path, or leave synchronization inactive to exercise the
 * immediate path — both with no DB involved.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
