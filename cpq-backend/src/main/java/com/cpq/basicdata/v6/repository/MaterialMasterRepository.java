package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.math.BigDecimal;
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

    /** 现状语义（preserveDescriptive=false：名称/类型非空覆盖）。核价 P05 / 单重沿用此重载，行为不变。 */
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
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  String productionNo, UUID updatedBy, boolean preserveDescriptive) {
        String nameClause = preserveDescriptive
            ? "COALESCE(material_master.material_name, EXCLUDED.material_name)"
            : "COALESCE(EXCLUDED.material_name, material_master.material_name)";
        String typeClause = preserveDescriptive
            ? "COALESCE(material_master.material_type, EXCLUDED.material_type)"
            : "COALESCE(EXCLUDED.material_type, material_master.material_type)";
        String sql =
            "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
            "  old_material_no, material_type, usage_property, unit_weight, standard_unit, production_no, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES (:materialNo, :materialName, :specification, :dimension, " +
            "  :oldMaterialNo, :materialType, :usageProperty, :unitWeight, :standardUnit, :productionNo, " +
            "  NOW(), NOW(), :updatedBy) " +
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
     */
    public void upsertBatchNameType(java.util.List<NameTypeRow> rows, UUID updatedBy, boolean preserveDescriptive) {
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
                    .append(", NOW(), NOW(), :u)");
            }
            String sql =
                "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
                "  old_material_no, material_type, usage_property, unit_weight, standard_unit, production_no, " +
                "  created_at, updated_at, updated_by) VALUES " + vals +
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
     */
    public void upsertBatchWithWeight(java.util.List<WeightRow> rows, UUID updatedBy) {
        if (rows == null || rows.isEmpty()) return;
        final int CHUNK = 500;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            java.util.List<WeightRow> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(:m").append(i)
                    .append(", NULL, NULL, NULL, NULL, NULL, NULL, CAST(:w").append(i)
                    .append(" AS numeric), NULL, NOW(), NOW(), :u)");
            }
            String sql =
                "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
                "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
                "  created_at, updated_at, updated_by) VALUES " + vals +
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
            q.executeUpdate();
        }
    }

    /**
     * 批量 upsert（仅 material_no 维度，无任何描述列），等价于对每行调
     * {@link #upsertByMaterialNo}(no, null×8, updatedBy, <b>true</b>) 的顺序结果（Q02 成品料号同步）。
     * 因全列 EXCLUDED 为 NULL、preserve=true → 冲突时所有 COALESCE 保留 existing，仅刷新 updated_at/updated_by，
     * 与 {@link #upsertBatchNameType}(NameTypeRow(no,null,null), updatedBy, true) <b>逐位等价</b> → 直接委托，避免重复 SQL。
     * 前提：<b>调用方先按 material_no 去重</b>。
     */
    public void upsertBatchMaterialNoOnly(java.util.List<String> materialNos, UUID updatedBy) {
        if (materialNos == null || materialNos.isEmpty()) return;
        java.util.List<NameTypeRow> rows = new java.util.ArrayList<>(materialNos.size());
        for (String no : materialNos) rows.add(new NameTypeRow(no, null, null));
        upsertBatchNameType(rows, updatedBy, true);
    }

    // =========================================================================
    // task-0721 B9：主档暂存（方案甲）—— pendingQuotationId 非 null 时改写暂存表，
    // 不直接落 material_master；核价通过时（B5）再 promoteStaging 覆盖式 upsert 进正式表。
    // 三个批量方法各加一个 pendingQuotationId 重载，call site 只需多传一个参数即可切换。
    // =========================================================================

    /** {@link #upsertBatchNameType(java.util.List, UUID, boolean)} 的 pending 感知重载：
     *  {@code pendingQuotationId==null} 时逐字节委派原方法（零回归）；非 null 时写暂存表，
     *  不落 material_master。 */
    public void upsertBatchNameType(java.util.List<NameTypeRow> rows, UUID updatedBy,
                                    boolean preserveDescriptive, UUID pendingQuotationId) {
        if (pendingQuotationId == null) {
            upsertBatchNameType(rows, updatedBy, preserveDescriptive);
            return;
        }
        if (rows == null || rows.isEmpty()) return;
        for (NameTypeRow r : rows) {
            stageOne(pendingQuotationId, r.materialNo(), r.materialName(), null, null, null,
                r.materialType(), null, null, null, r.productionNo(), updatedBy);
        }
    }

    /** {@link #upsertBatchWithWeight(java.util.List, UUID)} 的 pending 感知重载。 */
    public void upsertBatchWithWeight(java.util.List<WeightRow> rows, UUID updatedBy, UUID pendingQuotationId) {
        if (pendingQuotationId == null) {
            upsertBatchWithWeight(rows, updatedBy);
            return;
        }
        if (rows == null || rows.isEmpty()) return;
        for (WeightRow r : rows) {
            stageOne(pendingQuotationId, r.materialNo(), null, null, null, null,
                null, null, r.unitWeight(), null, null, updatedBy);
        }
    }

    /** {@link #upsertBatchMaterialNoOnly(java.util.List, UUID)} 的 pending 感知重载。 */
    public void upsertBatchMaterialNoOnly(java.util.List<String> materialNos, UUID updatedBy, UUID pendingQuotationId) {
        if (pendingQuotationId == null) {
            upsertBatchMaterialNoOnly(materialNos, updatedBy);
            return;
        }
        if (materialNos == null || materialNos.isEmpty()) return;
        for (String no : materialNos) {
            stageOne(pendingQuotationId, no, null, null, null, null, null, null, null, null, null, updatedBy);
        }
    }

    /**
     * 暂存一条主档变更（upsert 进 {@code pending_material_master_staging}，键=(quotation_id, material_no)）。
     * 描述性列（name/type/specification/dimension/old_material_no/usage_property/standard_unit/production_no）
     * 采用 <b>本单内首个非空胜</b>（{@code COALESCE(staging.现值, EXCLUDED.新值)}，对齐现网 5 处调用点
     * 里 4 处的 {@code preserveDescriptive=true} 语义）；{@code unit_weight} 采用 <b>末值非空胜</b>
     * （{@code COALESCE(EXCLUDED.新值, staging.现值)}，对齐 Q18 单重覆盖语义）。
     */
    private void stageOne(UUID quotationId, String materialNo, String materialName, String specification,
                          String dimension, String oldMaterialNo, String materialType, String usageProperty,
                          BigDecimal unitWeight, String standardUnit, String productionNo, UUID updatedBy) {
        if (quotationId == null || materialNo == null || materialNo.isBlank()) return;
        String sql =
            "INSERT INTO pending_material_master_staging (quotation_id, material_no, material_name, " +
            "  specification, dimension, old_material_no, material_type, usage_property, unit_weight, " +
            "  standard_unit, production_no, created_at, updated_at, updated_by) " +
            "VALUES (:qid, :materialNo, :materialName, :specification, :dimension, :oldMaterialNo, " +
            "  :materialType, :usageProperty, :unitWeight, :standardUnit, :productionNo, NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (quotation_id, material_no) DO UPDATE SET " +
            "  material_name    = COALESCE(pending_material_master_staging.material_name,    EXCLUDED.material_name), " +
            "  specification    = COALESCE(pending_material_master_staging.specification,    EXCLUDED.specification), " +
            "  dimension        = COALESCE(pending_material_master_staging.dimension,        EXCLUDED.dimension), " +
            "  old_material_no  = COALESCE(pending_material_master_staging.old_material_no,  EXCLUDED.old_material_no), " +
            "  material_type    = COALESCE(pending_material_master_staging.material_type,    EXCLUDED.material_type), " +
            "  usage_property   = COALESCE(pending_material_master_staging.usage_property,   EXCLUDED.usage_property), " +
            "  standard_unit    = COALESCE(pending_material_master_staging.standard_unit,    EXCLUDED.standard_unit), " +
            "  production_no    = COALESCE(pending_material_master_staging.production_no,    EXCLUDED.production_no), " +
            "  unit_weight      = COALESCE(EXCLUDED.unit_weight,      pending_material_master_staging.unit_weight), " +
            "  updated_at       = NOW(), " +
            "  updated_by       = EXCLUDED.updated_by";
        em.createNativeQuery(sql)
            .setParameter("qid", quotationId)
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
            .executeUpdate();
    }

    /** 暂存记录（B5/B6 读取用于回填/预览）。 */
    public record StagedRow(String materialNo, String materialName, String specification, String dimension,
                            String oldMaterialNo, String materialType, String usageProperty,
                            BigDecimal unitWeight, String standardUnit, String productionNo) {}

    /** 一次性读出该报价单全部暂存主档变更（B5/B6 用，一次 IN 查，非逐行）。 */
    @SuppressWarnings("unchecked")
    public java.util.List<StagedRow> listStaging(UUID quotationId) {
        if (quotationId == null) return java.util.List.of();
        java.util.List<Object[]> rows = em.createNativeQuery(
                "SELECT material_no, material_name, specification, dimension, old_material_no, " +
                "       material_type, usage_property, unit_weight, standard_unit, production_no " +
                "FROM pending_material_master_staging WHERE quotation_id = :qid")
            .setParameter("qid", quotationId)
            .getResultList();
        java.util.List<StagedRow> out = new java.util.ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new StagedRow((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                (String) r[5], (String) r[6], (BigDecimal) r[7], (String) r[8], (String) r[9]));
        }
        return out;
    }

    /**
     * B5/B9：核价通过时把该报价单全部暂存主档变更覆盖式 upsert 进 {@code material_master}
     * （{@code preserveDescriptive=true}，与现网 5 处直写调用点的既有语义一致——已存在的描述性
     * 字段不被空值/其它客户批次覆盖；本方法不清理暂存行，由调用方（{@code QuoteBackfillService}）
     * 在同事务内统一清理，避免部分成功部分残留）。
     *
     * @return 已 promote 的料号数
     */
    public int promoteStaging(UUID quotationId, UUID updatedBy) {
        java.util.List<StagedRow> staged = listStaging(quotationId);
        for (StagedRow s : staged) {
            upsertByMaterialNo(s.materialNo(), s.materialName(), s.specification(), s.dimension(),
                s.oldMaterialNo(), s.materialType(), s.usageProperty(), s.unitWeight(), s.standardUnit(),
                s.productionNo(), updatedBy, true);
        }
        return staged.size();
    }

    /** B8：报价单删除/重导前清理该单全部主档暂存（与 7 张版本化表 pending 行同生命周期）。 */
    public void clearStaging(UUID quotationId) {
        if (quotationId == null) return;
        em.createNativeQuery("DELETE FROM pending_material_master_staging WHERE quotation_id = :qid")
            .setParameter("qid", quotationId)
            .executeUpdate();
    }
}
