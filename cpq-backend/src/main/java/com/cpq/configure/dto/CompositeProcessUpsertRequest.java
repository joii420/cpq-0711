package com.cpq.configure.dto;

import java.util.List;

public class CompositeProcessUpsertRequest {
    public String code;
    public String name;
    public String icon;
    public String description;
    /** 结构化输入，Service 序列化为 JSON 存 paramSchema 列 */
    public List<ParamDef> paramSchema;
    public Integer sortOrder;
    /** ACTIVE / INACTIVE (check chk_composite_process_def_status) */
    public String status;

    public static class ParamDef {
        public String id;          // e.g. "pressure" / "current"
        public String label;       // e.g. "铆接压力"
        public String unit;        // e.g. "kN" — may be ""
        public String type;        // "number" | "text"
        public String placeholder; // 输入框占位符
    }
}
