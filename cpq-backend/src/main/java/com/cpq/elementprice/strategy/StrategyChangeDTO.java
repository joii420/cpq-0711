package com.cpq.elementprice.strategy;

/** 历史记录里单个变化字段（task-0722 · B9，契约见 api.md §7）。*/
public class StrategyChangeDTO {
    public String field;
    public String fieldLabel;
    public String oldValue;
    public String newValue;

    public StrategyChangeDTO() {}

    public StrategyChangeDTO(String field, String fieldLabel, String oldValue, String newValue) {
        this.field = field;
        this.fieldLabel = fieldLabel;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}
