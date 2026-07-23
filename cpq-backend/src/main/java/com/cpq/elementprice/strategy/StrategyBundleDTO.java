package com.cpq.elementprice.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** 某客户（或 _GLOBAL_）的全部策略（task-0722 · B8，契约见 api.md §5.1）。*/
public class StrategyBundleDTO {
    public String customerNo;

    /** JSON 字段名 "default"（Java 保留字，字段名加下划线 + @JsonProperty 显式指定序列化名）。*/
    @JsonProperty("default")
    public StrategyDTO default_;

    public List<StrategyDTO> exceptions = new ArrayList<>();
}
