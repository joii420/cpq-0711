package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** V6 §6 元素BOM子表（比 material_bom_item 多 content 含量字段）。 */
@Entity
@Table(name = "element_bom_item")
public class ElementBomItem extends V6BaseEntity {

    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    @Column(name = "customer_no", nullable = false, length = 20)
    public String customerNo;

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    /** V239: 宏丰成品料号 (Q04 Excel 第 1 列)；与 material_no(投入料号) 并存让 mirror SQL 按主件查。 */
    @Column(name = "hf_part_no", length = 20)
    public String hfPartNo;

    @Column(name = "characteristic", nullable = false, length = 100)
    public String characteristic;

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

    @Column(name = "seq_no")
    public Integer seqNo;

    @Column(name = "issue_unit", length = 20)
    public String issueUnit;

    @Column(name = "composition_qty", precision = 18, scale = 6)
    public BigDecimal compositionQty;

    @Column(name = "base_qty", precision = 18, scale = 6)
    public BigDecimal baseQty;

    @Column(name = "component_usage_type", length = 100)
    public String componentUsageType;

    @Column(name = "feature_mgmt", length = 20)
    public String featureMgmt;

    @Column(name = "content", precision = 18, scale = 6)
    public BigDecimal content;

    @Column(name = "upper_limit_pct", precision = 10, scale = 4)
    public BigDecimal upperLimitPct;

    @Column(name = "lower_limit_pct", precision = 10, scale = 4)
    public BigDecimal lowerLimitPct;

    @Column(name = "scrap_batch", precision = 18, scale = 6)
    public BigDecimal scrapBatch;

    @Column(name = "scrap_rate", precision = 10, scale = 4)
    public BigDecimal scrapRate;

    @Column(name = "defect_rate", precision = 10, scale = 4)
    public BigDecimal defectRate;

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

    @Column(name = "recovery_discount", precision = 10, scale = 4)
    public BigDecimal recoveryDiscount;

    @Column(name = "recovery_currency", length = 10)
    public String recoveryCurrency;

    @Column(name = "recovery_unit", length = 20)
    public String recoveryUnit;

    /** task-0721 B1：本行归属的未审核报价单（NULL=正式/历史；非 NULL=该报价单私有 pending 草稿）。 */
    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;

    /** task-0721 B1：pending 行点名它取代的旧 current 行 id 集合（供 B3 视图改写"遮蔽"用）。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pending_supersedes")
    public UUID[] pendingSupersedes;
}
