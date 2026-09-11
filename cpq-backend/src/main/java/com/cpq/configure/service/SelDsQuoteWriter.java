package com.cpq.configure.service;

import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.versioning.AxisKey;
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
 * 本类是选配写入侧<b>唯一</b>与新表打交道的地方，六张目标表
 * （task-260910 · B-1/B-2/B-3 新增后两张）：
 *
 * <table>
 *   <tr><th>表</th><th>版本化</th><th>写法</th><th>AC</th></tr>
 *   <tr><td>{@code ds_quote_material}</td><td>否</td><td>{@code ON CONFLICT DO NOTHING} 直插</td><td>A-1</td></tr>
 *   <tr><td>{@code ds_quote_customer_part}</td><td>否</td><td>裸 INSERT（要让 23505 冒出来）</td><td>A-6 / A-10</td></tr>
 *   <tr><td>{@code ds_quote_material_bom}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>A-2 / A-4</td></tr>
 *   <tr><td>{@code ds_quote_element_bom}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>A-3</td></tr>
 *   <tr><td>{@code ds_quote_self_process_fee}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>AC-1 / AC-4</td></tr>
 *   <tr><td>{@code ds_quote_assembly_fee}</td><td>是</td><td>{@link VersionedGroupWriter}</td><td>AC-2 / AC-3</td></tr>
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
     * 🚩 <b>事实仍成立</b>：V6 的第三态「成品」在新表值域里没有位置
     * （下游取数配置器发的是 {@code WHERE material_type IN ('零件','外购件')}，硬塞「成品」会污染值域）。
     * <b>但处置已被 2026-09-04 用户裁决改写</b>（需求文档 A-AC-7③）：组合产品父料号
     * （V6 时代写 {@code material_type='成品'}）<b>不再传 {@code null}，改为归入「零件」</b>
     * {@link #TYPE_PART} —— 用户原话「选配的数据的主产品在入库时，物料表中主产品的类型应该是[零件]」。
     * ⇒ 选配铸出的料号 {@code material_type} <b>不得为 NULL</b>（A-AC-7 判据：NULL 行数 = 0）。
     * 🚫 <b>旧注释「本方法传 null，不许拿『零件』凑数」已作废</b>，不要依据它把本行改回去。
     *
     * <p><b>A-11（A-AC-11）· {@code categoryCode}</b>（2026-09-04 用户裁决<b>新增</b>，
     * <b>覆盖</b>原「🚫 {@code category_code} 本方法刻意不写」那条裁决）：
     * 用户原话「新建产品的产品分类默认都归属[默认分类]」⇒ 选配铸出的所有新料号一律写
     * {@link #CATEGORY_DEFAULT}（{@code product_category.code='000000'}，名即「默认分类」）。
     * 口径<b>对齐导入侧</b>（实测现存 45 条 {@code source=IMPORT} 行全部是 {@code 000000}），
     * 不是新造规则。📌 该列 {@code compared=false} 且本表免版本
     * （无 {@code row_fingerprint} / {@code version_no} / {@code _history}）⇒ 写它不触发任何升版。
     *
     * <p>⚠️ 两个新值都受 {@code ON CONFLICT DO NOTHING} 约束：<b>料号已存在时不回填</b>
     * （例如库里早有的外购件料号）。A-AC-7 / A-AC-11 约束的是「本流程新建的行」，
     * 存量行的补齐属数据治理，不由选配顺手改写别人导入的数据。
     */
    public void upsertMaterial(String customerNo, String materialNo, String materialName, String specification,
                               String dimension, BigDecimal unitWeight, String materialType,
                               String categoryCode, String operator) {
        if (materialNo == null || materialNo.isBlank()) return;
        requireCustomer(customerNo, "ds_quote_material", materialNo);
        // 🆕 task-260907 · B-1/B-6：customer_no 进列清单，冲突目标随
        //    uq_ds_quote_material 一同扩成 (customer_no, material_no) ——
        //    同一料号在不同客户下各自一行，🚫 不再被对方的 DO NOTHING 吞掉。
        em.createNativeQuery(
                "INSERT INTO ds_quote_material (customer_no, material_no, material_name, specification, dimension, "
              + "  unit_weight, material_type, category_code, source, created_by, updated_at, updated_by) "
              + "VALUES (:cust, :mn, :nm, :sp, :dm, :uw, :mt, :cc, :src, :op, now(), :op) "
              + "ON CONFLICT (customer_no, material_no) DO NOTHING")
            .setParameter("cust", customerNo)
            .setParameter("mn", materialNo)
            .setParameter("nm", materialName)
            .setParameter("sp", specification)
            .setParameter("dm", dimension)
            .setParameter("uw", unitWeight)
            .setParameter("mt", materialType)
            .setParameter("cc", categoryCode)
            .setParameter("src", SOURCE)
            .setParameter("op", operator)
            .executeUpdate();
    }

    /** {@code ds_quote_material.material_type} 值域（{@code QuoteRegistry.MATERIAL_TYPE} 的镜像）。 */
    public static final String TYPE_PART = "零件";
    public static final String TYPE_OUTSOURCED = "外购件";

    /**
     * A-AC-11（2026-09-04 用户裁决）：选配铸出的新料号一律归「默认分类」。
     * <p>{@code product_category.code='000000'}，其 {@code name} 实测就是「默认分类」。
     * 🚫 别改成 {@code null} 或空串 —— A-AC-11 的判据是本流程新建行的
     * {@code category_code} 恒为 {@code 000000}。
     */
    public static final String CATEGORY_DEFAULT = "000000";

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
     *
     * <h3>task-260910 · D-14：无条件直写主表（与导入侧看齐）</h3>
     * 本方法<b>直写主表</b>，轴 {@code (customer_no, material_no)}，与导入侧
     * （{@code DatasetImportService} → {@code VersionedGroupWriter#writeGroups}）走同一个写入器、
     * 产出逐列同型的行。
     * <p>🚫 <b>不要再改回「只写 {@code _record}、等核价通过回填主表」</b>（原方案 ② / 原 D-7）。
     * 那个方案的理由是「新料号必须过核价审核才进主库」，已于 2026-09-10 被用户裁决 <b>D-14 推翻</b>：
     * 实测 {@code ds_quote_material_bom} 的 {@code source} 分布为
     * IMPORT 2734 / MANUAL 18 / QUOTE_BACKFILL 8 ⇒ <b>导入侧一直直写主表、一次核价审核都没过</b>，
     * 那条规则本来只约束选配一侧、并不成立。用户原话：
     * 「那选配与导入看齐，也写入相同的主表，两侧功能保持一致」。
     */
    public VersionedGroupWriter.Result writeMaterialBomGroup(String customerNo, String materialNo,
                                                            List<Map<String, Object>> rows,
                                                            String operator) {
        SheetDef sd = sheet("MATERIAL_BOM");
        return versionedWriter.writeGroup(sd, AxisKey.of(sd, customerNo, materialNo),
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
     * <p><b>task-260910 · D-14</b>：现状与写入<b>都是主表口径</b>（曾按方案 ② 改成 {@code _record}
     * 口径 + {@code appendRows}，已被 D-14 推翻回退，理由见 {@link #writeMaterialBomGroup}）。
     *
     * @return {@code null} 表示已存在、未写；否则为 {@code writeGroup} 的结果
     */
    @SuppressWarnings("unchecked")
    public VersionedGroupWriter.Result writeOutsourcedSelfRow(String customerNo, String materialNo, String operator) {
        if (materialNo == null || materialNo.isBlank()) return null;
        requireCustomer(customerNo, "ds_quote_material_bom", materialNo);
        // 🆕 task-260907 · B-3：读现状必须按【复合轴】收窄。
        //    只按 material_no 读 = 把别的客户的行读进来，再整组重写到本客户名下 —— 双向串数据。
        List<Object[]> cur = em.createNativeQuery(
                "SELECT item_seq, input_material_no, output_material_type, component_qty, material_ratio "
              + "FROM ds_quote_material_bom WHERE customer_no = :cust AND material_no = :mn ORDER BY item_seq")
            .setParameter("cust", customerNo)
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
        return writeMaterialBomGroup(customerNo, materialNo, rows, operator);
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
     *
     * <p><b>task-260910 · D-14</b>：<b>直写主表</b>（曾按方案 ② 改成只写
     * {@code ds_quote_element_bom_record}，已被 D-14 推翻回退，理由见 {@link #writeMaterialBomGroup}）。
     */
    public VersionedGroupWriter.Result writeElementBomGroup(String customerNo, String materialNo,
                                                           List<Map<String, Object>> rows,
                                                           String operator) {
        SheetDef sd = sheet("ELEMENT_BOM");
        return versionedWriter.writeGroup(sd, AxisKey.of(sd, customerNo, materialNo),
            rows == null ? List.of() : rows, SOURCE, REASON, operator);
    }

    // ══════════════════════════════════════════════════════════════════
    // task-260910 · B-1 / B-2 / B-3 · 工序与组装工艺 → 报价侧新表
    //   自制工序    → ds_quote_self_process_fee（原 V6 unit_price，cost_type='自制加工费'）
    //   组合工艺    → ds_quote_assembly_fee    （原 V6 capacity，resource_group_no='QUOTE_ASSEMBLY'）
    //   外购件工序  → ds_quote_assembly_fee    （🚨 D-6：费用类别由「自制加工费」变「组装加工费」）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 组装一行 {@code ds_quote_self_process_fee}（task-260910 · B-1，api.md §4 列映射）。
     *
     * <p>🚨 <b>{@code inputMaterialNo} 装的是「零件料号」</b>（D-4，用户原话：「{@code input_material_no}
     * 装的就是零件料号，就是新建的零件或者已有的零件的料号」）—— 逐字对应老 {@code unit_price.code}：
     * SIMPLE 时 = 料号自身；COMPOSITE 时 = 子件料号（轴列 {@code material_no} 则是父料号）。
     * 🚫 不要因为 {@code QuoteRegistry} 给这一列挂了 {@code .masterNoCheck("recipe", …)}
     * 就改填材质料号 —— 那个校验只在<b>导入</b>的表头/值域校验里生效（{@code DatasetImportValidator}），
     * 且它本身允许「编码域多态」（同一列既可能是材质也可能是零件）。
     *
     * <p>{@code item_seq} 与 {@code operation_item_seq} 同取行序（1..N）：前者是新表的通用项次
     * （{@code required=true}，老 {@code unit_price} 没有这一列），后者对应老 {@code seq_no}。
     * <p>{@code value}（单价）与 {@code ratio_pct} <b>不写</b> —— 选配阶段不采集单价，
     * 留 NULL 由后续维护/导入补（AC-1① 判据之一：{@code value IS NULL}）。
     */
    public static Map<String, Object> selfProcessFeeRow(String materialNo, int itemSeq, String inputMaterialNo,
                                                        String operationNo, String currency, String pricingUnit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("material_no", materialNo);          // 轴列（复合轴的第二维）
        r.put("item_seq", itemSeq);
        r.put("input_material_no", inputMaterialNo);
        r.put("operation_item_seq", itemSeq);
        r.put("operation_no", operationNo);
        r.put("currency", currency);
        r.put("pricing_unit", pricingUnit);
        return r;
    }

    /**
     * B-1（AC-1 / AC-4）：写一个销售料号的自制工序整组。
     *
     * <p>🚨 与 {@link #writeMaterialBomGroup} 同一条纪律：新表的轴是
     * {@code (customer_no, material_no)} <b>两维</b>，老 {@code unit_price} 的组键里那些
     * {@code system_type / price_type / cost_type / code} 维度<b>全部不存在</b>。
     * ⇒ COMPOSITE 的多个子件工序<b>必须一次给全</b>（各子件一行，靠 {@code input_material_no} 区分），
     * 🚫 不许在 for 循环里按子件逐次调本方法 —— 那样第二次会把第一次的行当成删除整组重写。
     */
    public VersionedGroupWriter.Result writeSelfProcessFeeGroup(String customerNo, String materialNo,
                                                                List<Map<String, Object>> rows,
                                                                String operator) {
        SheetDef sd = sheet("SELF_PROCESS_FEE");
        requireCustomer(customerNo, sd.tableName, materialNo);
        return versionedWriter.writeGroup(sd, AxisKey.of(sd, customerNo, materialNo),
            rows == null ? List.of() : rows, SOURCE, REASON, operator);
    }

    /**
     * 组装一行 {@code ds_quote_assembly_fee}（task-260910 · B-2 / B-3，api.md §4 列映射）。
     *
     * <p>🚨 <b>{@code assembly_fee} 恒写 {@code 0}</b>（D-5）：该列 {@code required=true}，
     * 而选配阶段<b>不采集单价</b>。{@code source='MANUAL'}（本类常量）是区分「选配占位、待补价」
     * 与「真实 0 元」的判据 —— AC-2③ 就是验这一对组合可同时查到。
     * 🚫 不要为了「更诚实」把它改成 NULL：Registry 里它是必填列，改 NULL 会让维护端/导入端校验红。
     *
     * <p><b>老 {@code capacity} 的两列在新表无落点</b>，🚫 不许硬塞进别的列：
     * {@code process_name}（可由 {@code assembly_operation} JOIN {@code process_master} 现算，本期不做）、
     * {@code production_type}（老实现写死常量 {@code BATCH_FIXED}，无业务消费方，不迁）。
     */
    public static Map<String, Object> assemblyFeeRow(String materialNo, int itemSeq, String assemblyOperation,
                                                     String currency, String pricingUnit,
                                                     BigDecimal defectRate) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("material_no", materialNo);
        r.put("item_seq", itemSeq);
        r.put("assembly_operation", assemblyOperation);
        r.put("assembly_fee", ASSEMBLY_FEE_PLACEHOLDER);
        r.put("currency", currency);
        r.put("pricing_unit", pricingUnit);
        r.put("defect_rate", defectRate);
        return r;
    }

    /** D-5：选配阶段的组装加工费占位值。🚫 不是 NULL（该列 {@code required=true}）。 */
    public static final BigDecimal ASSEMBLY_FEE_PLACEHOLDER = BigDecimal.ZERO;

    /**
     * B-2（AC-2）/ B-3（AC-3）：写一个销售料号的组装加工费整组。
     *
     * <p>两个来源共用本方法，且<b>轴值不同、互不干扰</b>：
     * <ul>
     *   <li>B-2 组合工艺：轴值 = <b>父</b>料号，行 = 各组合工序；</li>
     *   <li>B-3 外购件工序：轴值 = <b>外购件</b>料号，行 = 用户为该外购件选的工序。</li>
     * </ul>
     * ⚠️ 同一轴值上<b>整组重写</b>语义照旧 —— 调用方必须一次给全该轴值应有的全部行。
     */
    public VersionedGroupWriter.Result writeAssemblyFeeGroup(String customerNo, String materialNo,
                                                             List<Map<String, Object>> rows,
                                                             String operator) {
        SheetDef sd = sheet("ASSEMBLY_FEE");
        requireCustomer(customerNo, sd.tableName, materialNo);
        return versionedWriter.writeGroup(sd, AxisKey.of(sd, customerNo, materialNo),
            rows == null ? List.of() : rows, SOURCE, REASON, operator);
    }

    /**
     * task-260907 · AC-4：报价侧写入必须带客户号，🚫 不许静默写 NULL。
     * <p>选配链路的客户号来源是 {@code salesCtx.customerNo} / {@code customerCode}（= {@code customer.code}），
     * 与导入端「选客户」同源。
     */
    private static void requireCustomer(String customerNo, String table, String materialNo) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException(
                "报价侧写入必须提供客户编号（customer_no）: " + table + " 料号=" + materialNo);
        }
    }
}
