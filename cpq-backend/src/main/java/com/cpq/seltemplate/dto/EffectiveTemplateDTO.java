package com.cpq.seltemplate.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EffectiveTemplateDTO {
    public String customerNo;
    public String resolvedIndustryCode;   // 实际命中的模板行业码（可能是具体行业 / __DEFAULT__）
    public UUID templateId;               // null = 该客户行业与默认模板都没配
    public boolean usedDefault;           // true = 回退到 __DEFAULT__
    public boolean hasTemplate;           // false = 无任何模板可用（templateId=null）
    public List<Param> params = new ArrayList<>();  // 仅含 enabled=true 的参数

    public static class Param {
        public String paramTypeCode;   // MATERIAL / ELEMENT / PROCESS
        public String name;            // 材质 / 元素含量 / 工序
        public String valueMode;       // single / multi / adjust
        public List<Value> effectiveValues = new ArrayList<>();  // 限定后的可选值；adjust 类为空
    }

    public static class Value {
        public String key;    // 存入选配结果 / 指纹的值（材质code / 工序code）
        public String label;  // 展示
        public Value() {}
        public Value(String key, String label) { this.key = key; this.label = label; }
    }
}
