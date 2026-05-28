package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** V6 §4 物料BOM子表。 */
@Entity
@Table(name = "material_bom_item")
public class MaterialBomItem extends V6BaseEntity {

    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    @Column(name = "customer_no", nullable = false, length = 20)
    public String customerNo;

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "characteristic", length = 100)
    public String characteristic;

    @Column(name = "seq_no")
    public Integer seqNo;

    @Column(name = "component_no", length = 20)
    public String componentNo;

    @Column(name = "part_no", length = 20)
    public String partNo;

    @Column(name = "effective_datetime")
    public LocalDateTime effectiveDatetime;

    @Column(name = "expire_datetime")
    public LocalDateTime expireDatetime;

    @Column(name = "operation_no", length = 20)
    public String operationNo;

    @Column(name = "operation_seq", length = 20)
    public String operationSeq;

    @Column(name = "item_seq")
    public Integer itemSeq;

    @Column(name = "issue_unit", length = 20)
    public String issueUnit;

    @Column(name = "composition_qty", precision = 18, scale = 6)
    public BigDecimal compositionQty;

    @Column(name = "base_qty", precision = 18, scale = 6)
    public BigDecimal baseQty;

    @Column(name = "component_usage_type", length = 20)
    public String componentUsageType;

    @Column(name = "feature_mgmt", length = 20)
    public String featureMgmt;

    @Column(name = "upper_limit_pct", precision = 10, scale = 4)
    public BigDecimal upperLimitPct;

    @Column(name = "lower_limit_pct", precision = 10, scale = 4)
    public BigDecimal lowerLimitPct;

    @Column(name = "scrap_batch", precision = 18, scale = 6)
    public BigDecimal scrapBatch;

    @Column(name = "scrap_rate", precision = 10, scale = 4)
    public BigDecimal scrapRate;

    @Column(name = "fixed_scrap", precision = 18, scale = 6)
    public BigDecimal fixedScrap;

    @Column(name = "issue_location", length = 50)
    public String issueLocation;

    @Column(name = "issue_storage", length = 50)
    public String issueStorage;

    @Column(name = "fas_group", length = 20)
    public String fasGroup;

    @Column(name = "plug_position", length = 50)
    public String plugPosition;

    @Column(name = "ref_rd_center", length = 50)
    public String refRdCenter;

    @Column(name = "is_optional")
    public Boolean isOptional;

    @Column(name = "wo_expand_option", length = 20)
    public String woExpandOption;

    @Column(name = "is_purchase_replace")
    public Boolean isPurchaseReplace;

    @Column(name = "component_lead_time", precision = 18, scale = 6)
    public BigDecimal componentLeadTime;

    @Column(name = "main_substitute", length = 20)
    public String mainSubstitute;

    @Column(name = "attached_part", length = 20)
    public String attachedPart;

    @Column(name = "ecn_no", length = 30)
    public String ecnNo;

    @Column(name = "use_qty_formula")
    public Boolean useQtyFormula;

    @Column(name = "qty_formula", length = 500)
    public String qtyFormula;

    @Column(name = "scrap_rate_type", length = 20)
    public String scrapRateType;

    @Column(name = "is_backflush")
    public Boolean isBackflush;

    @Column(name = "is_customer_supply")
    public Boolean isCustomerSupply;

    @Column(name = "defect_rate", precision = 10, scale = 4)
    public BigDecimal defectRate;

    @Column(name = "calc_type", length = 20)
    public String calcType;

    @Column(name = "recovery_discount", precision = 10, scale = 4)
    public BigDecimal recoveryDiscount;

    @Column(name = "recovery_currency", length = 10)
    public String recoveryCurrency;

    @Column(name = "recovery_unit", length = 20)
    public String recoveryUnit;
}
