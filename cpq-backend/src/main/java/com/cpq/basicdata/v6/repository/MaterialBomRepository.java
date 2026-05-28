package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialBom;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MaterialBomRepository implements PanacheRepositoryBase<MaterialBom, UUID> {

    public Optional<MaterialBom> findOne(String systemType, String customerNo, String materialNo,
                                         String bomVersion, String characteristic) {
        return find("systemType = ?1 AND customerNo = ?2 AND materialNo = ?3 " +
                    "AND bomVersion = ?4 AND COALESCE(characteristic,'') = COALESCE(?5,'')",
                    systemType, customerNo, materialNo, bomVersion, characteristic)
               .firstResultOptional();
    }

    /** 找出主件料号在特定 (system, customer, bom_version) 下的特性最新版本（按字符序）。 */
    public Optional<String> findLatestCharacteristic(String systemType, String customerNo,
                                                      String materialNo, String bomVersion) {
        return find("systemType = ?1 AND customerNo = ?2 AND materialNo = ?3 AND bomVersion = ?4 " +
                    "ORDER BY characteristic DESC",
                    systemType, customerNo, materialNo, bomVersion)
               .firstResultOptional()
               .map(b -> b.characteristic);
    }
}
