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
                                  UUID updatedBy) {
        return upsertByMaterialNo(materialNo, materialName, specification, dimension, oldMaterialNo,
            materialType, usageProperty, unitWeight, standardUnit, updatedBy, false);
    }

    /**
     * Upsert material_master by material_no。
     * @param preserveDescriptive true=已存在则保留旧 material_name/material_type（仅空才回填）；
     *                            false=非空覆盖（现状语义）。其余列恒为非空覆盖。
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  UUID updatedBy, boolean preserveDescriptive) {
        String nameClause = preserveDescriptive
            ? "COALESCE(material_master.material_name, EXCLUDED.material_name)"
            : "COALESCE(EXCLUDED.material_name, material_master.material_name)";
        String typeClause = preserveDescriptive
            ? "COALESCE(material_master.material_type, EXCLUDED.material_type)"
            : "COALESCE(EXCLUDED.material_type, material_master.material_type)";
        String sql =
            "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
            "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES (:materialNo, :materialName, :specification, :dimension, " +
            "  :oldMaterialNo, :materialType, :usageProperty, :unitWeight, :standardUnit, " +
            "  NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (material_no) DO UPDATE SET " +
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
            .setParameter("updatedBy", updatedBy)
            .executeUpdate();
    }

    /** 批量 upsert 的一行（仅 material_no / material_name / material_type 维度，其余列恒 NULL）。 */
    public record NameTypeRow(String materialNo, String materialName, String materialType) {}

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
                    .append(", NULL, NULL, NULL, NOW(), NOW(), :u)");
            }
            String sql =
                "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
                "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
                "  created_at, updated_at, updated_by) VALUES " + vals +
                " ON CONFLICT (material_no) DO UPDATE SET " +
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
            }
            q.setParameter("u", updatedBy);
            q.executeUpdate();
        }
    }
}
