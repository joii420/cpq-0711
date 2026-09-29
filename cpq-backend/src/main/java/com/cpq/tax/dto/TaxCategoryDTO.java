package com.cpq.tax.dto;

import com.cpq.tax.entity.TaxCategory;
import java.math.BigDecimal;
import java.util.UUID;

public class TaxCategoryDTO {
    public UUID id;
    public String code;
    public String name;
    public String status;
    public Boolean isDefault;
    public BigDecimal effectiveVatRate;
    public UUID effectiveVersionId;

    public static TaxCategoryDTO from(TaxCategory c) {
        TaxCategoryDTO d = new TaxCategoryDTO();
        d.id = c.id; d.code = c.code; d.name = c.name; d.status = c.status; d.isDefault = c.isDefault;
        return d;
    }
}
