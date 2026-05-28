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

    /**
     * Upsert material_master by material_no.
     *
     * <p>非 NULL 入参覆盖现有列；NULL 列保留旧值（COALESCE 模式）。
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  UUID updatedBy) {
        String sql =
            "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
            "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES (:materialNo, :materialName, :specification, :dimension, " +
            "  :oldMaterialNo, :materialType, :usageProperty, :unitWeight, :standardUnit, " +
            "  NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (material_no) DO UPDATE SET " +
            "  material_name    = COALESCE(EXCLUDED.material_name,    material_master.material_name), " +
            "  specification    = COALESCE(EXCLUDED.specification,    material_master.specification), " +
            "  dimension        = COALESCE(EXCLUDED.dimension,        material_master.dimension), " +
            "  old_material_no  = COALESCE(EXCLUDED.old_material_no,  material_master.old_material_no), " +
            "  material_type    = COALESCE(EXCLUDED.material_type,    material_master.material_type), " +
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
}
