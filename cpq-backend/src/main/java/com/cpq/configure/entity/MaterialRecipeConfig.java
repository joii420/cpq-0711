package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 材质的含量配置（task-260901 · B-4，V399 建表）。
 *
 * <p>一个材质下挂 0..N 条配置，每条配置有自己的配置编号 {@code config_no = <材质编号>-<两位序号>}。
 * <ul>
 *   <li><b>M-1 发号</b>：{@code seq = max(该材质全部配置的 seq，含 INACTIVE) + 1}；
 *       格式化用 Java {@code String.format("%02d", seq)}（seq≥100 自然三位）。
 *       🚫 不得用 PG {@code lpad(x,2,'0')} —— 它把 '100' 截成 '10'。</li>
 *   <li><b>M-2 软删</b>：删除只把 {@code status} 置 INACTIVE，物理行保留、seq 水位不释放
 *       ⇒ 配置编号永不回收。</li>
 * </ul>
 */
@Entity
@Table(name = "material_recipe_config")
public class MaterialRecipeConfig extends PanacheEntityBase {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "recipe_id")
    public UUID recipeId;

    /** '00006-01' —— 全局唯一（uq_mrc_config_no） */
    @Column(name = "config_no")
    public String configNo;

    /** 发号水位（含 INACTIVE），与 recipeId 组成 uq_mrc_recipe_seq */
    public int seq;

    /** ACTIVE / INACTIVE */
    public String status;

    public String remark;

    @Column(name = "sort_order")
    public int sortOrder;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
