package com.cpq.quotation.service;

import com.cpq.common.PrecisionPolicy;

/**
 * Quote-output display precision resolved from the authenticated user's role.
 *
 * <p>This policy is deliberately pure and quote-specific. HTTP/session access stays in resources,
 * while calculation, persistence, snapshots and ordinary JSON responses remain untouched.
 */
public final class QuotationDisplayPrecisionPolicy {

    public static final int SALES_MAX_DECIMALS = 4;

    private QuotationDisplayPrecisionPolicy() {
    }

    public static int maxDecimalsForRole(String role) {
        if (role == null) return PrecisionPolicy.DISPLAY_SCALE;
        return switch (role) {
            case "SALES_REP", "SALES_MANAGER" -> SALES_MAX_DECIMALS;
            case "PRICING_MANAGER", "SYSTEM_ADMIN" -> PrecisionPolicy.DISPLAY_SCALE;
            default -> PrecisionPolicy.DISPLAY_SCALE;
        };
    }
}
