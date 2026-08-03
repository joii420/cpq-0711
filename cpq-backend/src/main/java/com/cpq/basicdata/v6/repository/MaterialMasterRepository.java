package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MaterialMasterRepository implements PanacheRepositoryBase<MaterialMaster, UUID> {

    @Inject
    EntityManager em;

    public Optional<MaterialMaster> findByMaterialNo(String materialNo) {
        return find("materialNo", materialNo).firstResultOptional();
    }

    /** 同名多条取 material_no 升序第一条（决策 #4）。 */
    public java.util.Optional<MaterialMaster> findFirstByMaterialName(String name) {
        return find("materialName = ?1 ORDER BY materialNo ASC", name).firstResultOptional();
    }

    /** 当前最大「恰好 10 位、9 字头」料号的数值；无则回退 8999999999（生成基数，+1=9000000000）。 */
    public long maxNineLeadingMaterialNo() {
        Object r = em.createNativeQuery(
            "SELECT COALESCE(MAX(material_no::bigint), 8999999999) " +
            "FROM material_master WHERE material_no ~ '^9[0-9]{9}$'")
            .getSingleResult();
        return ((Number) r).longValue();
    }

    /** 料号生成专用事务级 advisory lock（提交/回滚自动释放），串行化跨导入的「读 MAX→生成」窗口。 */
    public void lockForMaterialNoGeneration() {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
          .setParameter("k", MATERIAL_NO_GEN_LOCK_KEY)
          .getSingleResult();
    }
    private static final long MATERIAL_NO_GEN_LOCK_KEY = 906_000_000_001L;

    /** 现状语义（preserveDescriptive=false：名称/类型非空覆盖）。核价 P05 / 单重沿用此重载，行为不变。
     *  {@code pendingQuotationId} 恒为 null（直接落正式行）——见 13 参核心重载。 */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  String productionNo, UUID updatedBy) {
        return upsertByMaterialNo(materialNo, materialName, specification, dimension, oldMaterialNo,
            materialType, usageProperty, unitWeight, standardUnit, productionNo, updatedBy, false);
    }

    /**
     * Upsert material_master by material_no。
     * @param preserveDescriptive true=已存在则保留旧 material_name/material_type（仅空才回填）；
     *                            false=非空覆盖（现状语义）。其余列恒为非空覆盖。
     *                            {@code pendingQuotationId} 恒为 null（直接落正式行）——见 13 参核心重载。
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  String productionNo, UUID updatedBy, boolean preserveDescriptive) {
        return upsertByMaterialNo(materialNo, materialName, specification, dimension, oldMaterialNo,
            materialType, usageProperty, unitWeight, standardUnit, productionNo, updatedBy,
            preserveDescriptive, null);
    }

    /**
     * repair-0726 B2：Upsert material_master by material_no（pending 感知核心实现）。
     * <b>当前无外部调用方</b>——单行 pending 写入场景实际由三个批量方法（upsertBatchNameType/
     * upsertBatchWithWeight/upsertBatchMaterialNoOnly）承担，12 参重载恒以
     * {@code pendingQuotationId=null} 委托到本方法。保留此重载是为了让 upsertByMaterialNo 与三个
     * 批量方法保持同构（都存在"pending 感知核心 + null 委托壳"的形状），以备未来出现单行 pending
     * 写入场景，不代表当前存在一条活的单行 pending 路径。
     * @param preserveDescriptive true=已存在则保留旧 material_name/material_type（仅空才回填）；
     *                            false=非空覆盖（现状语义）。其余列恒为非空覆盖。
     * @param pendingQuotationId 仅在 INSERT 分支生效——料号首次出现时打上该未核准报价单归属；
     *                           null=直接落正式行（现状语义，核价 P 系列 / 回填补桩沿用）。
     *                           <b>UPDATE 分支（ON CONFLICT）不改写 pending_quotation_id</b>：
     *                           已存在的正式行不降级为 pending，已属于别的 pending 单的行不被抢占。
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  String productionNo, UUID updatedBy, boolean preserveDescriptive,
                                  UUID pendingQuotationId) {
        String nameClause = preserveDescriptive
            ? "COALESCE(material_master.material_name, EXCLUDED.material_name)"
            : "COALESCE(EXCLUDED.material_name, material_master.material_name)";
        String typeClause = preserveDescriptive
            ? "COALESCE(material_master.material_type, EXCLUDED.material_type)"
            : "COALESCE(EXCLUDED.material_type, material_master.material_type)";
        String sql =
            "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
            "  old_material_no, material_type, usage_property, unit_weight, standard_unit, production_no, " +
            "  created_at, updated_at, updated_by, pending_quotation_id) " +
            "VALUES (:materialNo, :materialName, :specification, :dimension, " +
            "  :oldMaterialNo, :materialType, :usageProperty, :unitWeight, :standardUnit, :productionNo, " +
            "  NOW(), NOW(), :updatedBy, :pq) " +
            // 故意不写 pending_quotation_id：已存在的正式行不降级、已属别单 pending 的行不被抢占。
            "ON CONFLICT (material_no) DO UPDATE SET " +
            "  production_no    = COALESCE(EXCLUDED.production_no,    material_master.production_no), " +
            "  material_name    = " + nameClause + ", " +
            "  material_type    = " + typeClause + ", " +
            "  specification    = COALESCE(EXCLUDED.specification,    material_master.specification), " +
            "  dimension        = COALESCE(EXCLUDED.dimension,        material_master.dimension), " +
            "  old_material_no  = COALESCE(EXCLUDED.old_material_no,  material_master.old_material_no), " +
            "  usage_property   = COALESCE(EXCLUDED.usage_property,   material_master.usage_property), " +
            "  unit_weight      = COALESCE(EXCLUDED.unit_weight,      material_master.unit_weight), " +
            "  standard_unit    = COALESCE(EXCLUDED.standard_unit,    material_master.standard_unit), " +
            "  updated_at       = NOW(), " +
            "  updated_by       = EXCLUDED.updated_by";
        return em.createNativeQuery(sql)
            .setParameter("materialNo", materialNo)
            .setParameter("materialName", materialName)
            .setParameter("specification", specification)
            .setParameter("dimension", dimension)
            .setParameter("oldMaterialNo", oldMaterialNo)
            .setParameter("materialType", materialType)
            .setParameter("usageProperty", usageProperty)
            .setParameter("unitWeight", unitWeight)
            .setParameter("standardUnit", standardUnit)
            .setParameter("productionNo", productionNo)
            .setParameter("updatedBy", updatedBy)
            .setParameter("pq", pendingQuotationId)
            .executeUpdate();
    }

    /** 批量 upsert 的一行（material_no / material_name / material_type / production_no 维度，其余列恒 NULL）。
     *  3 参构造保留兼容既有调用点(productionNo=null); repair-1 主料号登记用 4 参传生产料号。 */
    public record NameTypeRow(String materialNo, String materialName, String materialType, String productionNo) {
        public NameTypeRow(String materialNo, String materialName, String materialType) {
            this(materialNo, materialName, materialType, null);
        }
    }

    /** 批量 upsert 的一行（仅 material_no / unit_weight 维度，其余列恒 NULL）。 */
    public record WeightRow(String materialNo, BigDecimal unitWeight) {}

    /**
     * 累积 name/type 的 <b>首个非空</b>（按 material_no 去重归并）。与逐行
     * {@link #upsertByMaterialNo}(no, name, ..., type, ..., updatedBy, <b>true</b>) 的
     * {@code COALESCE(existing, new)} 顺序语义等价：同一 material_no 多次出现取遍历顺序里第一个非空值。
     * 供各 name/type 类 handler(Q04/06/07/08/09/10/13) 共用，避免逐处复制。
     */
    public static void accNameType(java.util.Map<String, String[]> acc, String no, String name, String type) {
        String[] cur = acc.get(no);
        if (cur == null) {
            acc.put(no, new String[]{name, type});
        } else {
            if (cur[0] == null) cur[0] = name;
            if (cur[1] == null) cur[1] = type;
        }
    }

    /**
     * 批量 upsert（name/type 维度，其余列恒 NULL），等价于对每行调
     * {@link #upsertByMaterialNo}(materialNo, name, null,null,null, type, null,null,null, updatedBy, preserveDescriptive)
     * 的顺序结果——前提：<b>调用方必须先按 material_no 去重</b>（PG 不允许同一冲突键在一条 INSERT 命中两次），
     * 且按"首个非空"归并 name/type（与逐行 COALESCE(existing,new) 链等价）。
     * 其余列在 EXCLUDED 恒为 NULL → COALESCE(NULL, existing)=existing，与逐行传 null 完全一致。
     * now() 在事务内恒定 → created_at/updated_at 与逐行一致。按 {@code CHUNK} 分块防 PG 65535 参数上限。
     * {@code pendingQuotationId} 恒为 null（直接落正式行）——见 4 参核心重载。
     */
    public void upsertBatchNameType(java.util.List<NameTypeRow> rows, UUID updatedBy, boolean preserveDescriptive) {
        upsertBatchNameType(rows, updatedBy, preserveDescriptive, null);
    }

    /**
     * repair-0726 B2：{@link #upsertBatchNameType(java.util.List, UUID, boolean)} 的 pending 感知
     * 核心实现——直接落 {@code material_master} 正表（不再改道暂存表），仅在 INSERT 分支多带一列
     * {@code pending_quotation_id}；{@code pendingQuotationId==null} 时该列写 NULL，与旧行为逐字节一致。
     * ON CONFLICT 分支不改写该列：已存在的正式行不降级，已属别单 pending 的行不被抢占。
     */
    public void upsertBatchNameType(java.util.List<NameTypeRow> rows, UUID updatedBy, boolean preserveDescriptive,
                                    UUID pendingQuotationId) {
        if (rows == null || rows.isEmpty()) return;
        String nameClause = preserveDescriptive
            ? "COALESCE(material_master.material_name, EXCLUDED.material_name)"
            : "COALESCE(EXCLUDED.material_name, material_master.material_name)";
        String typeClause = preserveDescriptive
            ? "COALESCE(material_master.material_type, EXCLUDED.material_type)"
            : "COALESCE(EXCLUDED.material_type, material_master.material_type)";
        final int CHUNK = 500;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            java.util.List<NameTypeRow> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(:m").append(i).append(", :n").append(i)
                    .append(", NULL, NULL, NULL, :t").append(i)
                    .append(", NULL, NULL, NULL, :p").append(i)
                    .append(", NOW(), NOW(), :u, :pq)");
            }
            String sql =
                "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
                "  old_material_no, material_type, usage_property, unit_weight, standard_unit, production_no, " +
                "  created_at, updated_at, updated_by, pending_quotation_id) VALUES " + vals +
                // 故意不写 pending_quotation_id：已存在的正式行不降级、已属别单 pending 的行不被抢占。
                " ON CONFLICT (material_no) DO UPDATE SET " +
                "  production_no    = COALESCE(EXCLUDED.production_no,    material_master.production_no), " +
                "  material_name    = " + nameClause + ", " +
                "  material_type    = " + typeClause + ", " +
                "  specification    = COALESCE(EXCLUDED.specification,    material_master.specification), " +
                "  dimension        = COALESCE(EXCLUDED.dimension,        material_master.dimension), " +
                "  old_material_no  = COALESCE(EXCLUDED.old_material_no,  material_master.old_material_no), " +
                "  usage_property   = COALESCE(EXCLUDED.usage_property,   material_master.usage_property), " +
                "  unit_weight      = COALESCE(EXCLUDED.unit_weight,      material_master.unit_weight), " +
                "  standard_unit    = COALESCE(EXCLUDED.standard_unit,    material_master.standard_unit), " +
                "  updated_at       = NOW(), " +
                "  updated_by       = EXCLUDED.updated_by";
            var q = em.createNativeQuery(sql);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i).materialNo());
                q.setParameter("n" + i, chunk.get(i).materialName());
                q.setParameter("t" + i, chunk.get(i).materialType());
                q.setParameter("p" + i, chunk.get(i).productionNo());
            }
            q.setParameter("u", updatedBy);
            q.setParameter("pq", pendingQuotationId);
            q.executeUpdate();
        }
    }

    /**
     * 批量 upsert（unit_weight 维度，其余列恒 NULL），等价于对每行调
     * {@link #upsertByMaterialNo}(no, null,null,null,null,null,null, unitWeight, null, updatedBy)
     * （10 参重载 = preserveDescriptive=false）的顺序结果——前提：<b>调用方先按 material_no 去重</b>，
     * 且 unit_weight 按 <b>末值非空胜</b> 归并（因 unit_weight 等非描述列在 ON CONFLICT 恒
     * {@code COALESCE(EXCLUDED, existing)} → 后到的非空值覆盖，与 preserveDescriptive 无关；
     * 尾随 null 不覆盖；仅出现过 null 权重的料号也须保留以建行）。
     * name/type 在 EXCLUDED 恒 NULL → {@code COALESCE(NULL, existing)=existing}，逐行/批量一致。
     * unit_weight 用 {@code CAST(:w AS numeric)} 显式标注类型，防多行 VALUES 首行 NULL 致 PG 无法推断列类型。
     * 按 {@code CHUNK} 分块防 PG 65535 参数上限。
     * {@code pendingQuotationId} 恒为 null（直接落正式行）——见 3 参核心重载。
     */
    public void upsertBatchWithWeight(java.util.List<WeightRow> rows, UUID updatedBy) {
        upsertBatchWithWeight(rows, updatedBy, null);
    }

    /**
     * repair-0726 B2：{@link #upsertBatchWithWeight(java.util.List, UUID)} 的 pending 感知核心实现——
     * 直接落 {@code material_master} 正表，仅在 INSERT 分支多带一列 {@code pending_quotation_id}；
     * 语义同 {@link #upsertBatchNameType(java.util.List, UUID, boolean, UUID)}。
     */
    public void upsertBatchWithWeight(java.util.List<WeightRow> rows, UUID updatedBy, UUID pendingQuotationId) {
        if (rows == null || rows.isEmpty()) return;
        final int CHUNK = 500;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            java.util.List<WeightRow> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(:m").append(i)
                    .append(", NULL, NULL, NULL, NULL, NULL, NULL, CAST(:w").append(i)
                    .append(" AS numeric), NULL, NOW(), NOW(), :u, :pq)");
            }
            String sql =
                "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
                "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
                "  created_at, updated_at, updated_by, pending_quotation_id) VALUES " + vals +
                // 故意不写 pending_quotation_id：已存在的正式行不降级、已属别单 pending 的行不被抢占。
                " ON CONFLICT (material_no) DO UPDATE SET " +
                "  material_name    = COALESCE(EXCLUDED.material_name,    material_master.material_name), " +
                "  material_type    = COALESCE(EXCLUDED.material_type,    material_master.material_type), " +
                "  specification    = COALESCE(EXCLUDED.specification,    material_master.specification), " +
                "  dimension        = COALESCE(EXCLUDED.dimension,        material_master.dimension), " +
                "  old_material_no  = COALESCE(EXCLUDED.old_material_no,  material_master.old_material_no), " +
                "  usage_property   = COALESCE(EXCLUDED.usage_property,   material_master.usage_property), " +
                "  unit_weight      = COALESCE(EXCLUDED.unit_weight,      material_master.unit_weight), " +
                "  standard_unit    = COALESCE(EXCLUDED.standard_unit,    material_master.standard_unit), " +
                "  updated_at       = NOW(), " +
                "  updated_by       = EXCLUDED.updated_by";
            var q = em.createNativeQuery(sql);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i).materialNo());
                q.setParameter("w" + i, chunk.get(i).unitWeight());
            }
            q.setParameter("u", updatedBy);
            q.setParameter("pq", pendingQuotationId);
            q.executeUpdate();
        }
    }

    /**
     * 批量 upsert（仅 material_no 维度，无任何描述列），等价于对每行调
     * {@link #upsertByMaterialNo}(no, null×8, updatedBy, <b>true</b>) 的顺序结果（Q02 成品料号同步）。
     * 因全列 EXCLUDED 为 NULL、preserve=true → 冲突时所有 COALESCE 保留 existing，仅刷新 updated_at/updated_by，
     * 与 {@link #upsertBatchNameType}(NameTypeRow(no,null,null), updatedBy, true) <b>逐位等价</b> → 直接委托，避免重复 SQL。
     * 前提：<b>调用方先按 material_no 去重</b>。{@code pendingQuotationId} 恒为 null——见 3 参核心重载。
     */
    public void upsertBatchMaterialNoOnly(java.util.List<String> materialNos, UUID updatedBy) {
        upsertBatchMaterialNoOnly(materialNos, updatedBy, null);
    }

    /** {@link #upsertBatchMaterialNoOnly(java.util.List, UUID)} 的 pending 感知重载——继续委托
     *  {@link #upsertBatchNameType(java.util.List, UUID, boolean, UUID)}（preserve=true），
     *  pendingQuotationId 原样透传。 */
    public void upsertBatchMaterialNoOnly(java.util.List<String> materialNos, UUID updatedBy, UUID pendingQuotationId) {
        if (materialNos == null || materialNos.isEmpty()) return;
        java.util.List<NameTypeRow> rows = new java.util.ArrayList<>(materialNos.size());
        for (String no : materialNos) rows.add(new NameTypeRow(no, null, null));
        upsertBatchNameType(rows, updatedBy, true, pendingQuotationId);
    }

    // =========================================================================
    // repair-0726 B2：料件类投入料号直落正表 + 行级 pending_quotation_id 标记 ——
    // 取代 task-0721 B9「主档暂存（方案甲）」。三个批量方法 + 单行 upsertByMaterialNo
    // 均改为「继续走原批量/单行 upsert SQL，只在 INSERT 分支多带一列 pending_quotation_id」，
    // 不再改道 pending_material_master_staging（V362 已随迁移退役）。
    // pending 标记生命周期复用现有 8 张 V6 表的 pending 基建：
    //   - 过户（导入建单）：V6QuotationCommitService#repointPendingOwnership 加入同一循环
    //   - 重导清理：QuoteImportService#clearPreviousPending 追加 deletePendingWithGuard
    //   - 核价通过转正：QuoteBackfillService#execute 用 flipPending 取代 promoteStaging
    //   - 删单回收：QuotationService#cleanupPendingV6Data 追加 deletePendingWithGuard
    // =========================================================================

    /** repair-0726 B2.2：核价通过——本单 pending 料号转正（不改其余列）。返回转正行数。 */
    public int flipPending(UUID quotationId) {
        if (quotationId == null) return 0;
        return em.createNativeQuery(
                "UPDATE material_master SET pending_quotation_id = NULL, updated_at = NOW() " +
                "WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId)
            .executeUpdate();
    }

    /**
     * repair-0726 B2.2/B2.3：重导覆盖 / 删单回收——删除本单 pending 料号行，带引用守卫。
     * 只查 3 处引用（不是全表扫描"任何数据"）：料件类料号只可能作为
     * {@code material_bom_item.component_no}（BOM 子件）出现；成品/主件料号必伴随
     * {@code material_bom.material_no}（BOM 母件）或 {@code material_customer_map.material_no}
     * （客户映射/占号行）。其余带 material_no 的 pending 表（element_bom / element_bom_item /
     * capacity / unit_price…）在本语义下不构成独立引用，未纳入检查。
     * <b>新增第 9 张 pending 表时需重新评估此清单是否仍然够用。</b>
     * 每处检查都排除本单自己的 pending 行（{@code <> :qid}）——见调用方 javadoc 的顺序说明。
     * 返回删除行数。
     */
    public int deletePendingWithGuard(UUID quotationId) {
        if (quotationId == null) return 0;
        return em.createNativeQuery(
                "DELETE FROM material_master mm " +
                "WHERE mm.pending_quotation_id = :qid " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom_item x " +
                "                   WHERE x.component_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid)) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom x " +
                "                   WHERE x.material_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid)) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_customer_map x " +
                "                   WHERE x.material_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid))")
            .setParameter("qid", quotationId)
            .executeUpdate();
    }

    /**
     * BL-0092：删除<b>孤儿</b> pending 料号行（归属的报价单已不存在），带引用守卫。
     *
     * <p>与 {@link #deletePendingWithGuard} 是同一套守卫语义的"悬空版"，两者只差筛选条件：
     * 前者筛"属于某张单"，本方法筛"归属的单已不存在"。守卫的三处引用检查逐条对称改写——
     * 原判据是「引用方不属于本单」（{@code pending_quotation_id IS NULL OR <> :qid}），
     * 这里对应为「引用方是有效数据」，即<b>正式行（列为 NULL）或归属的单仍存在</b>；
     * 若引用方自己也是孤儿，则不构成有效引用（它自己也在本轮清理范围内）。
     *
     * <p>用途见 {@code PendingHygieneService} 类注释：{@code pending_quotation_id} 无外键约束，
     * 迁库/DBA 直删/守卫有意留存都会产生孤儿，本方法是兜底回收的一环。
     *
     * @return 实际删除行数（被守卫拦下的不计入，调用方应再查一次剩余量并告警）
     */
    public int deleteOrphanPendingWithGuard() {
        return em.createNativeQuery(
                "DELETE FROM material_master mm " +
                "WHERE mm.pending_quotation_id IS NOT NULL " +
                "  AND NOT EXISTS (SELECT 1 FROM quotation q WHERE q.id = mm.pending_quotation_id) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom_item x " +
                "                   WHERE x.component_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL " +
                "                          OR EXISTS (SELECT 1 FROM quotation q2 WHERE q2.id = x.pending_quotation_id))) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom x " +
                "                   WHERE x.material_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL " +
                "                          OR EXISTS (SELECT 1 FROM quotation q2 WHERE q2.id = x.pending_quotation_id))) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_customer_map x " +
                "                   WHERE x.material_no = mm.material_no " +
                "                     AND (x.pending_quotation_id IS NULL " +
                "                          OR EXISTS (SELECT 1 FROM quotation q2 WHERE q2.id = x.pending_quotation_id)))")
            .executeUpdate();
    }

    /** 主档记录（B5/B6 读取用于回填/预览；字段形状沿用暂存表时代的命名，避免连锁改
     *  {@code QuoteBackfillPlan}/{@code QuoteBackfillPreviewService}）。 */
    public record StagedRow(String materialNo, String materialName, String specification, String dimension,
                            String oldMaterialNo, String materialType, String usageProperty,
                            BigDecimal unitWeight, String standardUnit, String productionNo) {}

    /**
     * repair-0726 B2.2/B6：一次性读出该报价单全部 pending 主档行（替代 task-0721 B9 的
     * {@code listStaging}，供回填预览/执行读取）。<b>固定 {@code ORDER BY material_no}</b>——
     * 确定性排序：token 计算侧（{@code QuoteBackfillPreviewService}）已对 canonical 字符串做
     * {@code Collections.sort}，token 本身不依赖这里的顺序；此处排序只是不让原生查询结果集顺序
     * 随 PG 执行计划漂移，属良好实践而非 token 正确性的必要条件。
     */
    @SuppressWarnings("unchecked")
    public List<StagedRow> listPending(UUID quotationId) {
        if (quotationId == null) return List.of();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT material_no, material_name, specification, dimension, old_material_no, " +
                "       material_type, usage_property, unit_weight, standard_unit, production_no " +
                "FROM material_master WHERE pending_quotation_id = :qid " +
                "ORDER BY material_no")
            .setParameter("qid", quotationId)
            .getResultList();
        List<StagedRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new StagedRow((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                (String) r[5], (String) r[6], (BigDecimal) r[7], (String) r[8], (String) r[9]));
        }
        return out;
    }
}
