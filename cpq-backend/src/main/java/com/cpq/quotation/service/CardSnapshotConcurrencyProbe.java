package com.cpq.quotation.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/** Test synchronization seam; production calls return immediately. */
@ApplicationScoped
public class CardSnapshotConcurrencyProbe {

    public void afterEnsureValuesBuilt(UUID quotationId) {
        // Tests replace this bean with a latch-backed mock.
    }

    public void beforeEditLockWait(UUID quotationId) {
        // Tests replace this bean with a latch-backed mock.
    }
}
