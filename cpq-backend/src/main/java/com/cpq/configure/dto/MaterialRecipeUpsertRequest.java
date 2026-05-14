package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.List;

public class MaterialRecipeUpsertRequest {
    public String code;
    public String symbol;
    public String name;
    public String specLabel;
    public String recipeType;       // 'locked' | 'editable' | 'partial'
    public Integer sortOrder;
    public String status;           // 'ACTIVE' | 'INACTIVE'
    public List<ElementUpsert> elements;

    public static class ElementUpsert {
        public String elementCode;
        public String elementName;
        public BigDecimal defaultPct;
        public BigDecimal minPct;
        public BigDecimal maxPct;
        public Boolean isLocked;
        public Integer sortOrder;
    }
}
