package com.cpq.existingproduct.dto;

/**
 * 报价单「从已有产品添加」列表行（task-260909，api.md §1.3）。
 *
 * <p>数据源 = 单表 {@code ds_quote_customer_part}（按本报价单客户过滤），
 * 一条 {@code LEFT JOIN v_compat_material_master} 带出主数据品名与规格。
 *
 * <p>🔧 task-260909 契约变更（跨端，前端 {@code cpq-frontend/src/types/existingProduct.ts} 同步）：
 * <ul>
 *   <li>🆕 新增 {@link #customerDrawingNo}；</li>
 *   <li>🔧 {@link #productName} 与 {@link #customerMaterialName} <b>拆成两个不同的值</b>
 *       （改动前两者同源，前端两列渲染必然相同）；</li>
 *   <li>❌ 删除 {@code has3d} / {@code thumbnailUrl} —— 3D 预览已从本抽屉移除（D-4），
 *       {@code model_config} 的 JOIN 一并删除。3D 模型管理功能本身不受影响。</li>
 * </ul>
 */
public class ExistingProductDTO {

    /** 销售料号（= {@code ds_quote_customer_part.material_no}）。 */
    public String materialNo;

    /**
     * 客户产品编号 —— <b>代表编号</b>（一料号多编号时取 created_at 最早的那个）。
     *
     * <p>🚫 <b>保留单值语义、不要改名/改类型</b>：前端与既有用例都在读它。
     * 要拿全部编号请用 {@link #customerProductNos}。
     */
    public String customerProductNo;

    /**
     * 该 {@code (customer_no, material_no)} 名下的<b>全部</b>客户产品编号，按 {@code created_at} 升序；
     * 来源 = {@code ds_quote_customer_part}（与主查询同源）。
     *
     * <p>🚨 <b>为什么必须有它</b>：列表按销售料号去重（一行只出现一次，防 AP-22 重复渲染），
     * 于是 {@link #customerProductNo} 只能显示一个代表编号。但「一料号多编号」是真实场景 ——
     * 用第二个编号的销售不带过滤打开列表，看到的是别人的编号，会<b>认不出这是自己的产品</b>。
     * ⇒ 去重保留，编号全给（用户裁决 2026-09-03）。
     */
    public java.util.List<String> customerProductNos = new java.util.ArrayList<>();

    /**
     * 客户图号（= {@code ds_quote_customer_part.customer_drawing_no}）；无则 null，前端渲染 {@code —}。
     * <p>🆕 task-260909 AC-4 新增。
     */
    public String customerDrawingNo;

    /**
     * 客户物料名称 —— <b>客户怎么叫这个件</b>（= {@code ds_quote_customer_part.customer_part_name}）。
     * 无则 null，前端渲染 {@code —}。
     */
    public String customerMaterialName;

    /**
     * 品名 —— <b>主数据里的品名</b>：{@code COALESCE(NULLIF(v_compat_material_master.material_name,''),
     * material_no)}，主数据无品名时回退销售料号（保证品名列不空白）。
     *
     * <p>🚫 <b>兜底链里不许出现 {@code customer_part_name}</b>：那会让本字段与
     * {@link #customerMaterialName} 在客户名有值时又变回相同（AC-5 直接失败）。
     */
    public String productName;

    /** 规格：{@code COALESCE(NULLIF(v_compat_material_master.specification,''), dimension)}。 */
    public String spec;

    /**
     * 来源标签（AC-6，<b>按来源列判定</b>，不再按「客户产品编号是否为空」）：
     * {@code source='IMPORT'} → {@code EXISTING}（导入建档，前端标「已有」）；
     * 其余（{@code MANUAL}）→ {@code CONFIGURED}（选配产出，前端标「选配」）。
     */
    public String source;

    /** 选配产品类型：{@code SIMPLE} | {@code COMPOSITE}（取自 {@code sel_part_signature} 最近一条），非选配为 null。 */
    public String configProductType;
}
