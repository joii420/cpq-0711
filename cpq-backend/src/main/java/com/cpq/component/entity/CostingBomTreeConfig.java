package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 全局可配置「核价树递归 SQL」配置。
 *
 * <p>全局同一时刻最多一条 {@code isActive=true}（DB 部分唯一索引 {@code ux_cbt_active} 保障）。
 * 递归 SQL 契约：输入具名参数 {@code :production_part_nos}（text[]），
 * 输出列逐字 {@code root_no / material_no / bom_version / parent_no}。
 * 详见 {@code docs/} 核价树渲染重构相关方案文档。
 */
@Entity
@Table(name = "costing_bom_tree_config")
public class CostingBomTreeConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT")
    public String sqlTemplate;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = false;

    /**
     * 递归 SQL 配置的<b>数据集维度</b>。每个 usage 至多一条 {@code isActive=true}
     * （DB 部分唯一索引 {@code uq_bom_tree_config_active_per_usage} 按 usage 分别约束，见 V346）。
     *
     * <p>task-260909 B-2 值域（{@code api.md §1.1}）：
     * {@code QUOTE}(报价) / {@code COST_BASIC}(基础核价) / {@code COST_DETAIL}(详细核价)；
     * {@code COSTING} 是 {@code COST_BASIC} 的<b>只读兼容别名</b>，<b>不可再写入</b>。
     *
     * <p>🚨 <b>本列没有 DB 层 CHECK 约束</b>（2026-09-09 实测 {@code pg_constraint} 仅有主键）——
     * 值域<b>只由 {@link com.cpq.component.service.CostingBomTreeConfigService#normalizeUsage} 一处把关</b>。
     * 绕过 Service 直接 {@code persist()} / 直接 SQL 写入不会有任何报错，写进去的野值会让
     * {@link #findActive(String)} 永远找不到它 —— 症状是"配置明明在列表里，渲染却说未配置"。
     *
     * <p>⚠️ 字段名 {@code usage} 在 Java 侧不是保留字，但 {@code java.lang.Record#usage} 之类联想会误导；
     * 列名与字段名逐字相同，改名会同时打断前端 DTO 契约与本列的部分唯一索引语义，不要动。
     */
    @Column(nullable = false, length = 16)
    public String usage = "COSTING";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * 当前生效配置（按 usage 维度）；无 → null。
     *
     * <p>🚨 <b>入参必须是已归一的 canonical usage</b>（{@code QUOTE} / {@code COST_BASIC} /
     * {@code COST_DETAIL}）—— 本方法做的是<b>字面量</b>相等匹配，传 {@code "COSTING"} 只会去找
     * {@code usage='COSTING'} 的行，<b>不会</b>命中 {@code COST_BASIC} 的生效配置。
     * 归一唯一入口是 {@link com.cpq.component.service.CostingBomTreeConfigService#normalizeUsage}，
     * 调用方（{@code BomTreeRenderService}）必须先过它再进来。
     */
    public static CostingBomTreeConfig findActive(String usage) {
        return find("isActive = true and usage = ?1", usage).firstResult();
    }
}
