package com.cpq.elementprice.pricetable;

/** 历史记录里单个变化字段（update-0724 · B5，契约见 api.md §5；形状逐字比照 StrategyChangeDTO）。*/
public class PriceChangeDTO {
    public String field;
    public String fieldLabel;
    public String oldValue;
    public String newValue;

    public PriceChangeDTO() {}

    public PriceChangeDTO(String field, String fieldLabel, String oldValue, String newValue) {
        this.field = field;
        this.fieldLabel = fieldLabel;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}
