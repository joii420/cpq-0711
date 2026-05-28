package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** V6 §10 汇率表（实际表名 exchange_rate_v6，避让 V44 老 exchange_rate）。 */
@Entity
@Table(name = "exchange_rate_v6")
public class ExchangeRateV6 extends V6BaseEntity {

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "base_currency", nullable = false, length = 10)
    public String baseCurrency;

    @Column(name = "target_currency", nullable = false, length = 10)
    public String targetCurrency;

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    public BigDecimal rate;

    @Column(name = "ref_rate", precision = 18, scale = 8)
    public BigDecimal refRate;

    @Column(name = "ref_fetch_rule", length = 200)
    public String refFetchRule;

    @Column(name = "ref_source_url", length = 500)
    public String refSourceUrl;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;
}
