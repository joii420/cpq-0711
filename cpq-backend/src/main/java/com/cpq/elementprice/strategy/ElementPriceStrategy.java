package com.cpq.elementprice.strategy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 客户元素价格策略（task-0722 · B8）。{@code elementCode IS NULL} = 客户级默认行；
 * 非空 = 元素级例外。{@code customerNo} 是真实客户编码或字面量 {@code _GLOBAL_}（§11.11.4），
 * 全链路一律用 String，不转 UUID。
 */
@Entity
@Table(name = "element_price_strategy")
public class ElementPriceStrategy extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no")
    public String customerNo;

    @Column(name = "element_code")
    public String elementCode;

    @Column(name = "source_id")
    public UUID sourceId;

    public String method;

    @Column(name = "window_num")
    public Integer windowNum;

    @Column(name = "window_unit")
    public String windowUnit;

    /** task-0813：定价乘数系数（原始价 × factor + premium），归入族 B(含量·占比·比率)扩容为 12 位小数。 */
    @Column(precision = 18, scale = 12)
    public BigDecimal factor;

    /** task-0813：此前 @Column 未声明 precision/scale（对应 DB 列 element_price_strategy.premium）；
     *  本次按目标类型补齐声明，使 T6 反射一致性测试可覆盖该列。 */
    @Column(name = "premium", precision = 26, scale = 12)
    public BigDecimal premium;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    public UUID updatedBy;
}
