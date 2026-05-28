package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.UnitPrice;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UnitPriceRepository implements PanacheRepositoryBase<UnitPrice, UUID> {

    /** 业务键：(system_type, price_type, version_no, code, customer_no, supplier_no, effective_date) */
    public Optional<UnitPrice> findOne(String systemType, String priceType, String versionNo,
                                       String code, String customerNo, String supplierNo) {
        return find("systemType = ?1 AND priceType = ?2 AND versionNo = ?3 AND code = ?4 " +
                    "AND COALESCE(customerNo,'') = COALESCE(?5,'') " +
                    "AND COALESCE(supplierNo,'') = COALESCE(?6,'')",
                    systemType, priceType, versionNo, code, customerNo, supplierNo)
               .firstResultOptional();
    }
}
