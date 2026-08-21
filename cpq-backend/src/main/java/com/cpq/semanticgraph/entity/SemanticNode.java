package com.cpq.semanticgraph.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 语义图 · 节点（task-260819 B-1）。
 *
 * <p>17 张导入 Sheet + 5 张查名维表 + 1 个表函数，{@code node_kind} 区分 SHEET / LOOKUP / FUNCTION。
 * D-27：DB 唯一真源，代码里不再保留任何声明。种子数据见 V388 迁移。
 *
 * <p>⚠️ {@code short_name} / {@code discriminator} 两列是 {@code 需求文档.md §4.5} 草案 DDL 之外的补充：
 * 前者是 D-13 别名生成（{@code _<Sheet简称>_<列名>}）的必需输入，后者是 api.md §1.1 响应契约已声明、
 * 草案 DDL 遗漏的字段（AC-43 要求展示判别式）。两处补充已在 backtask 回报中说明。
 */
@Entity
@Table(name = "semantic_node", uniqueConstraints = @UniqueConstraint(columnNames = {"node_key", "dialect"}))
public class SemanticNode extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "node_key", nullable = false, length = 80)
    public String nodeKey;

    @Column(name = "display_name", nullable = false, length = 200)
    public String displayName;

    @Column(name = "short_name", nullable = false, length = 40)
    public String shortName;

    /** SHEET | LOOKUP | FUNCTION */
    @Column(name = "node_kind", nullable = false, length = 20)
    public String nodeKind;

    @Column(name = "physical_table", length = 120)
    public String physicalTable;

    /** FULL = customer_no + is_current + system_type；NONE = 无该三件套收窄 */
    @Column(name = "scope", nullable = false, length = 20)
    public String scope = "NONE";

    @Column(name = "anchor_expr", length = 200)
    public String anchorExpr;

    @Column(name = "grain_columns", nullable = false, columnDefinition = "TEXT[]")
    public String[] grainColumns = new String[0];

    @Column(name = "fixed_predicate", columnDefinition = "TEXT")
    public String fixedPredicate;

    @Column(name = "func_signature", columnDefinition = "TEXT")
    public String funcSignature;

    @Column(name = "discriminator", columnDefinition = "TEXT")
    public String discriminator;

    /** 对账断言用（AC-36）：产出本节点数据的 {@code Q*Handler}/{@code MaterialBomMergeHandler} 类名。 */
    @Column(name = "source_handler", length = 120)
    public String sourceHandler;

    /** QUOTE | COSTING | BOTH（D-19，二期填核价侧靠它） */
    @Column(name = "dialect", nullable = false, length = 20)
    public String dialect = "QUOTE";

    @Column(name = "note", columnDefinition = "TEXT")
    public String note;

    @Column(name = "created_by", length = 80)
    public String createdBy;
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_by", length = 80)
    public String updatedBy;
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();
    @Column(name = "status", nullable = false, length = 20)
    public String status = "ACTIVE";
}
