package com.cpq.existingproduct.dto;

/**
 * 报价单「从已有产品添加」列表行（task-0712 B3，api.md §2.1）。
 *
 * <p>数据源 {@code material_customer_map}（按本报价单客户过滤），two LEFT JOIN 一次带出：
 * <ul>
 *   <li>{@code material_master} → {@link #spec}（{@code COALESCE(NULLIF(specification,''), dimension)}，
 *       决策 3-A）；</li>
 *   <li>{@code model_config}（{@code subject_type='SALES_PART' AND is_current}）→ {@link #has3d} /
 *       {@link #thumbnailUrl}。</li>
 * </ul>
 * {@link #productName} 与 {@link #customerMaterialName} 同源 {@code material_customer_map.customer_material_name}
 * （api.md §2.1 示例注释：productName ← customer_material_name；表本身无独立"通用品名"列，两字段供前端分别映射到
 * {@code CustomerPartCandidate.partName} / {@code customerPartName} 两个槽位，见 fronttask.md §F4 落行映射）。
 */
public class ExistingProductDTO {

    /** 销售料号（= material_customer_map.material_no）。 */
    public String materialNo;

    /** 客户产品编号。占位行（选配发号副作用）此列为 NULL，已在服务层过滤排除（F005）。 */
    public String customerProductNo;

    /** 品名（= customer_material_name）。 */
    public String productName;

    /** 规格：COALESCE(NULLIF(material_master.specification,''), material_master.dimension)。 */
    public String spec;

    /** 客户物料名称（= customer_material_name，与 productName 同源）。 */
    public String customerMaterialName;

    /** 该料号是否配了当前版本 3D 模型（model_config is_current 命中）。 */
    public Boolean has3d;

    /** 3D 缩略图 URL；无则 null。 */
    public String thumbnailUrl;
}
