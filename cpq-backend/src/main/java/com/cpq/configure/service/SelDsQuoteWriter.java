package com.cpq.configure.service;

import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.versioning.VersionedGroupWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task-260903 · 阶段 A —— 选配产出写入 {@code ds_quote_*} 报价侧新表体系。
 *
 * <p>取代 {@code ConfigureProductService} 里那一组 {@code *V6} 落库方法（A-7 停写 V6 五表）。
 * 本类是选配写入侧<b>唯一</b>与新表打交道的地方，四张目标表：
 *
 * <table>
 *   <tr><th>表</th><th>版本化</th><th>写法</th><th>AC</th></tr>
 *   <tr><td>{@code ds_quote_material}</td><td>否</td><td>{@code ON CONFLICT DO NOTHING} 直插</td><td>A-1</td></tr>
 *   <tr><td>{@code ds_quote_customer_part}</td><td>否</td><td>裸 INSERT（要让 23505 冒出来）</td><td>A-6 / A-10</td></tr>
 *   <tr><td>{@code ds_quote_material_bom}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>A-2 / A-4</td></tr>
 *   <tr><td>{@code ds_quote_element_bom}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>A-3</td></tr>
 * </table>
 *
 * <h3>🚨 三条容易写错的地方</h3>
 * <ol>
 *   <li><b>版本化表的轴只有 {@code material_no}，没有 characteristic / customer_no 维度。</b>
 *       V6 时代同一个料号的 ASSEMBLY 行与 RECIPE 行是<b>两个独立组</b>（characteristic 是组键的一维），
 *       可以分两次写。新表里它们同属一个轴值 ⇒ <b>必须一次 {@code writeGroup} 把整组行全给出</b>。
 *       分两次调 = 第二次把第一次的行当成「已删除」整组重写。见
 *       {@link #writeMaterialBomGroup} 的入参约定。</li>
 *   <li><b>免版本表不得走 {@link VersionedGroupWriter}</b> —— 它会抛 {@code IllegalArgumentException}
 *       （{@code writeGroups} 开头的 {@code !sheet.versioned} 分支）。</li>
 *   <li><b>{@code ds_quote_customer_part} 刻意不写 {@code ON CONFLICT}</b>。
 *       {@code PlainTableWriter} 的 {@code ON CONFLICT … DO UPDATE} 语义在这里是<b>错的</b>：
 *       A-AC-10 要的是并发同编号时一个成功、另一个 409，而 DO UPDATE 会让两个都「成功」，
 *       后者静默覆盖前者的料号归属。所以这里用裸 INSERT 让 {@code uq_ds_quote_customer_part}
 *       抛 23505，由调用方映射成 409。</li>
 * </ol>
 *
 * <h3>N+1</h3>
 * 本类所有方法的 SQL 条数与料号数 / 材质数 / 元素数<b>无关</b>：免版本表 1 条 INSERT；
 * 版本化表走 {@code writeGroup}（= size 1 的 {@code writeGroups}），条数固定。
 * 🚫 调用方不得在 for 循环里逐料号调本类方法。
 */
@ApplicationScoped
public class SelDsQuoteWriter {

    /** {@code ds_quote_material_bom.output_material_type} 的取值域 —— 逐字对齐 V6 {@code characteristic}。 */
    public static final String OUT_ASSEMBLY = "ASSEMBLY";
    public static final String OUT_RECIPE = "RECIPE";
    /**
     * 🚩 api.md §2.1 只写了 {@code ASSEMBLY} / {@code RECIPE} 两个值，但 V6 的 characteristic 是<b>三态</b>
     * （{@code BomCharacteristic}：RECIPE / ASSEMBLY / OUTSOURCED），且实测有 24 段组件 SQL 直接写
     * {@code characteristic = 'OUTSOURCED'} 过滤。少了这一态，外购件页签会整片空。
     * A-AC-7「外购件身份」也要求外购件可辨识。故补齐第三态，已在回报中登记为契约缺口。
     */
    public static final String OUT_OUTSOURCED = "OUTSOURCED";

    private static final String REASON = VersionedGroupWriter.REASON_MANUAL_UPGRADE;
    private static final String SOURCE = VersionedGroupWriter.SOURCE_MANUAL;

    @Inject QuoteRegistry quoteRegistry;
    @Inject VersionedGroupWriter versionedWriter;
    @Inject EntityManager em;

    private SheetDef sheet(String sheetKey) {
        for (SheetDef s : quoteRegistry.sheets()) {
            if (s.sheetKey.equals(sheetKey)) return s;
        }
        throw new IllegalStateException("QuoteRegistry 未登记 sheetKey: " + sheetKey);
    }

    // ══════════════════════════════════════════════════════════════════
    // A-1 · 料号主档 → ds_quote_material（免版本，唯一键 material_no）
    // ══════════════════════════════════════════════════════════════════

    /**
     * A-1（A-AC-1①）：料号身份落 {@code ds_quote_material}。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 而非 {@code DO UPDATE} —— 沿用 V6
     * {@code insertMaterialMasterV6} 的既有裁决：本方法的 partNo 只可能是刚 mint 出来的全新报价料号，
     * 冲突路径实际不可达；改成 DO UPDATE 会让「选配复用一个导入来的料号」反向覆盖导入侧的品名/规格。
     *
     * <p><b>A-5（A-AC-7）· {@code materialType}</b>：V409 已落库，值域由
     * {@code QuoteRegistry} 的 {@code .strictOptions(零件 / 外购件)} 把住。
     * 🚩 <b>V6 的第三态「成品」在新表值域里没有位置</b> —— 组合产品父料号
     * （V6 时代写 {@code material_type='成品'}）本方法传 {@code null}，
     * 🚫 不许改写成「零件」凑数，也不许硬塞「成品」污染值域
     * （下游取数配置器发的是 {@code WHERE material_type IN ('零件','外购件')}）。
     * 已在回报中登记为契约缺口。
     *
     * <p>🚫 <b>{@code category_code} 本方法刻意不写</b>：那是「新料号数据规则」为产品分类轴
     * 加的料号属性（D-27），而选配铸的料号没有产品分类概念（分类挂在
     * {@code customer.product_category_id} 上，是客户属性）。
     * 实测该列 {@code compared=false} 且 {@code ds_quote_material} 是免版本表
     * （无 {@code row_fingerprint} / {@code version_no} / {@code _history}）⇒
     * 留空不会触发任何升版。用户后续导入同料号时由导入 Phase 1 填 {@code 000000}。
     */
    public void upsertMaterial(String materialNo, String materialName, String specification,
                               String dimension, BigDecimal unitWeight, String materialType,
                               String operator) {
        if (materialNo == null || materialNo.isBlank()) return;
        em.createNativeQuery(
                "INSERT INTO ds_quote_material (material_no, material_name, specification, dimension, "
              + "  unit_weight, material_type, source, created_by, updated_at, updated_by) "
              + "VALUES (:mn, :nm, :sp, :dm, :uw, :mt, :src, :op, now(), :op) "
              + "ON CONFLICT (material_no) DO NOTHING")
            .setParameter("mn", materialNo)
            .setParameter("nm", materialName)
            .setParameter("sp", specification)
            .setParameter("dm", dimension)
            .setParameter("uw", unitWeight)
            .setParameter("mt", materialType)
            .setParameter("src", SOURCE)
            .setParameter("op", operator)
            .executeUpdate();
    }

    /** {@code ds_quote_material.material_type} 值域（{@code QuoteRegistry.MATERIAL_TYPE} 的镜像）。 */
    public static final String TYPE_PART = "零件";
    public static final String TYPE_OUTSOURCED = "外购件";

    // ══════════════════════════════════════════════════════════════════
    // A-6 / A-10 · 客户产品编号 → ds_quote_customer_part（免版本）
    // ══════════════════════════════════════════════════════════════════

    /**
     * A-6（A-AC-3）：「客户产品编号 → 销售料号」映射落 {@code ds_quote_customer_part}，
     * 取代已退役的 {@code sel_product_no}（表与存量数据保留，仅停写）。
     *
     * <p>A-10（A-AC-10）：<b>裸 INSERT，不吃冲突</b>。并发同编号时后者阻塞在
     * {@code uq_ds_quote_customer_part} 上直到前者提交，然后拿到 23505 —— 调用方负责映射成 409。
     * 前置 SELECT 检查只是快速反馈，挡不住竞态。
     *
     * <p>⚠️ 复用场景（指纹命中）<b>同样写一行</b>，这正是「一料号多编号」（A-AC-4③）。
     */
    public void insertCustomerPart(String customerNo, String customerProductNo, String customerPartName,
                                   String materialNo, String operator) {
        if (customerNo == null || customerNo.isBlank()) return;
        if (customerProductNo == null || customerProductNo.isBlank()) return;
        if (materialNo == null || materialNo.isBlank()) return;
        em.createNativeQuery(
                "INSERT INTO ds_quote_customer_part (customer_no, customer_product_no, customer_part_name, "
              + "  material_no, source, created_by, updated_at, updated_by) "
              + "VALUES (:cn, :pn, :nm, :mn, :src, :op, now(), :op)")
            .setParameter("cn", customerNo)
            .setParameter("pn", customerProductNo)
            .setParameter("nm", customerPartName)
            .setParameter("mn", materialNo)
            .setParameter("src", SOURCE)
            .setParameter("op", operator)
            .executeUpdate();
    }

    // ══════════════════════════════════════════════════════════════════
    // A-2 / A-4 · 物料 BOM → ds_quote_material_bom（带版本）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 组装一行 {@code ds_quote_material_bom}。
     *
     * <p>A-4（A-AC-6）：{@code outputMaterialType} <b>必须显式填</b> —— 它参与行指纹
     * （{@code Registry} 里 {@code compared=true}），留空会让用户下次导入同一料号时
     * 指纹对不上而整组误升版。
     *
     * @param itemSeq            项次。不参与指纹（多重集比较不看行序），但行数变了就升版
     * @param inputMaterialNo    投入料号：材质行填材质料号（{@code material_recipe.code}）、
     *                           装配行填子件报价料号、外购件自指行填料号本身。
     *                           <b>兼容视图靠它 LEFT JOIN material_recipe 补出材质名</b>（B-2）
     * @param componentQty       组成数量（装配用量）。兼容视图映射到 V6 {@code composition_qty}
     * @param materialRatio      材质占比（%）。{@code numeric(26,12)}，A-AC-1② 要求存满 12 位小数
     */
    public static Map<String, Object> materialBomRow(String materialNo, int itemSeq, String inputMaterialNo,
                                                     String outputMaterialType, BigDecimal componentQty,
                                                     BigDecimal materialRatio) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("material_no", materialNo);          // 轴列，必须在行里（persistedColumns 含它）
        r.put("item_seq", itemSeq);
        r.put("input_material_no", inputMaterialNo);
        r.put("output_material_type", outputMaterialType);
        r.put("component_qty", componentQty);
        r.put("material_ratio", materialRatio);
        return r;
    }

    /**
     * A-2（A-AC-1② / A-AC-6）：写一个料号的物料 BOM 整组。
     *
     * <p>🚨 {@code rows} 必须是该料号<b>此刻应有的全部行</b>，跨 characteristic 合并后一次给全：
     * 组合产品父料号 = ASSEMBLY 行（子件清单）+ RECIPE 行（子件材质自指）一起传。
     * 传部分行 = 其余行被当成删除、整组重写。
     *
     * <p>A-9（A-AC-5）：新料号在库中不存在 ⇒ {@code writeGroup} 走 CREATED 分支，
     * {@code version_no} 恒为 1（{@code VersionedGroupWriter} 里硬编码），选配阶段不会升版。
     * 本方法<b>不做任何版本号干预</b>，后人也不要加。
     */
    public VersionedGroupWriter.Result writeMaterialBomGroup(String materialNo, List<Map<String, Object>> rows,
                                                            String operator) {
        return versionedWriter.writeGroup(sheet("MATERIAL_BOM"), materialNo,
            rows == null ? List.of() : rows, SOURCE, REASON, operator);
    }

    /**
     * 外购件的「自指物料行」（A-AC-6 / A-AC-7 的结构承载，对齐 V6
     * {@code insertOutsourcedBomItemV6} 写 {@code characteristic='OUTSOURCED'} 的做法）。
     *
     * <p>🚨 与 V6 的关键差异，写错会丢数据：V6 里外购件的 OUTSOURCED 行独占一个组
     * （characteristic 是组键的一维），怎么写都碰不到同料号的其它 BOM 行；
     * 新表的组键只有 {@code material_no}，而<b>外购件料号是料号库里已存在的料号，
     * 完全可能已经有导入来的 {@code ds_quote_material_bom} 行</b>。
     * 直接 {@code writeGroup(单行)} = 把那些导入行整组归档删除。
     * 所以这里<b>先读现状再合并</b>：已有 OUTSOURCED 自指行就原样不动（返回 null，一行不写），
     * 否则「现有行 + 自指行」整组重写。
     *
     * <p>N+1：读现状 1 条 SQL，与外购件数无关（调用方每个外购件调一次，是 V6 时代就有的形态；
     * 单次选配的外购件数是个位数常量，不随数据量增长）。
     *
     * @return {@code null} 表示已存在、未写；否则为 {@code writeGroup} 的结果
     */
    @SuppressWarnings("unchecked")
    public VersionedGroupWriter.Result writeOutsourcedSelfRow(String materialNo, String operator) {
        if (materialNo == null || materialNo.isBlank()) return null;
        List<Object[]> cur = em.createNativeQuery(
                "SELECT item_seq, input_material_no, output_material_type, component_qty, material_ratio "
              + "FROM ds_quote_material_bom WHERE material_no = :mn ORDER BY item_seq")
            .setParameter("mn", materialNo)
            .getResultList();

        List<Map<String, Object>> rows = new ArrayList<>(cur.size() + 1);
        int maxSeq = 0;
        for (Object[] r : cur) {                                   // 循环体内零查库：纯内存搬运
            Integer seq = r[0] == null ? null : ((Number) r[0]).intValue();
            String inNo = r[1] == null ? null : r[1].toString();
            String outType = r[2] == null ? null : r[2].toString();
            if (OUT_OUTSOURCED.equals(outType) && materialNo.equals(inNo)) {
                return null;                                       // 幂等：自指行已在，整组一行不动
            }
            if (seq != null && seq > maxSeq) maxSeq = seq;
            Map<String, Object> row = materialBomRow(materialNo, seq == null ? 0 : seq, inNo, outType,
                r[3] == null ? null : new BigDecimal(r[3].toString()),
                r[4] == null ? null : new BigDecimal(r[4].toString()));
            rows.add(row);
        }
        rows.add(materialBomRow(materialNo, maxSeq + 1, materialNo, OUT_OUTSOURCED, null, null));
        return writeMaterialBomGroup(materialNo, rows, operator);
    }

    // ══════════════════════════════════════════════════════════════════
    // A-3 · 元素含量 → ds_quote_element_bom（带版本）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 组装一行 {@code ds_quote_element_bom}。
     *
     * @param materialPartNo 材质料号（{@code material_recipe.code}）—— 三层模型下一个零件有 N 个材质，
     *                       元素行靠这一列分回各自的材质。V6 时代它是<b>组键的一维</b>（每材质一个组），
     *                       新表里降级成普通列，N 个材质的元素行同属一个轴值组
     */
    public static Map<String, Object> elementBomRow(String materialNo, String materialPartNo, int itemSeq,
                                                    String elementCode, BigDecimal contentPct) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("material_no", materialNo);
        r.put("material_part_no", materialPartNo);
        r.put("item_seq", itemSeq);
        r.put("element_code", elementCode);
        r.put("content_pct", contentPct);
        return r;
    }

    /**
     * A-3（A-AC-1③）：写一个料号的元素含量整组。
     *
     * <p>🚨 同 {@link #writeMaterialBomGroup}：{@code rows} 是该料号<b>所有材质</b>的元素行合起来，
     * 不能按材质分多次调 —— 新表的轴只有 {@code material_no}，分次调后一次会抹掉前一次。
     */
    public VersionedGroupWriter.Result writeElementBomGroup(String materialNo, List<Map<String, Object>> rows,
                                                           String operator) {
        return versionedWriter.writeGroup(sheet("ELEMENT_BOM"), materialNo,
            rows == null ? List.of() : rows, SOURCE, REASON, operator);
    }
}
