package com.cpq.masterdata.dto;

import java.util.List;

/**
 * Top-level response for GET /api/cpq/master-data/overview.
 */
public class MasterDataOverviewDTO {

    /** Optional customer UUID used to scope CUSTOMER-group tables; null = global view. */
    public String customerId;

    /** Summaries for all 13 registered tables. */
    public List<TableSummaryDTO> tables;
}
