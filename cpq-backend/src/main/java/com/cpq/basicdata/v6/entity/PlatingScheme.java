package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** V6 §9 电镀方案表。业务键 (scheme_no, scheme_version, seq_no) UNIQUE。 */
@Entity
@Table(name = "plating_scheme")
public class PlatingScheme extends V6BaseEntity {

    @Column(name = "scheme_no", nullable = false, length = 20)
    public String schemeNo;

    @Column(name = "scheme_version", nullable = false, length = 20)
    public String schemeVersion;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "plating_element", nullable = false, length = 20)
    public String platingElement;

    @Column(name = "plating_method", nullable = false, length = 30)
    public String platingMethod;

    @Column(name = "surface_area", nullable = false, precision = 18, scale = 6)
    public BigDecimal surfaceArea;

    @Column(name = "plating_area", precision = 18, scale = 6)
    public BigDecimal platingArea;

    @Column(name = "plating_thickness", nullable = false, precision = 18, scale = 6)
    public BigDecimal platingThickness;

    @Column(name = "plating_requirement", length = 200)
    public String platingRequirement;

    @Column(name = "density", precision = 18, scale = 6)
    public BigDecimal density;

    @Column(name = "element_usage", nullable = false, precision = 18, scale = 6)
    public BigDecimal elementUsage;

    @Column(name = "element_usage_unit", length = 20)
    public String elementUsageUnit;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;

    @Column(name = "source_url", length = 500)
    public String sourceUrl;

    @Column(name = "source_name", length = 100)
    public String sourceName;

    @Column(name = "fetch_rule", length = 200)
    public String fetchRule;

    /** task-0721 B1：本行归属的未审核报价单（NULL=正式/历史；非 NULL=该报价单私有 pending 草稿）。 */
    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;

    /** task-0721 B1：pending 行点名它取代的旧 current 行 id 集合（供 B3 视图改写"遮蔽"用）。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pending_supersedes")
    public UUID[] pendingSupersedes;
}
