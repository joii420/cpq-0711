package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个列的渲染 + 校验元数据（api.md §2 sheets.columns）。
 *
 * <p>role 语义：
 * <ul>
 *   <li>{@code AXIS}  — 轴（system_type/price_type/锚料号 等），锁定不可改、由服务端注入，通常不出现在 columns。</li>
 *   <li>{@code SUBDIM}— 子维度编码列（工序号/元素码/来料料号），入 content、可改、走下拉。</li>
 *   <li>{@code VALUE} — 普通值列（单价/币种/不良率…），入 content、可改。</li>
 *   <li>{@code NAME}  — 只读名称列（关联主表带出），不入 content、不进指纹比对。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ColumnDef {

    public String name;
    public String label;
    public String type;      // STRING/NUMBER/DECIMAL/BOOLEAN/ENUM
    public String role;      // AXIS/SUBDIM/VALUE/NAME
    public boolean editable;
    public Dropdown dropdown;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Dropdown {
        public String kind;          // MASTER / ENUM / FREE
        public String master;        // MASTER 时: process/element/material
        public String nameColumn;    // 联动只读名称列（§4.4.0）
        public List<String> options; // ENUM 时的候选值

        public static Dropdown master(String master, String nameColumn) {
            Dropdown d = new Dropdown();
            d.kind = "MASTER"; d.master = master; d.nameColumn = nameColumn;
            return d;
        }
        public static Dropdown enumOf(List<String> options) {
            Dropdown d = new Dropdown();
            d.kind = "ENUM"; d.options = options;
            return d;
        }
        public static Dropdown free() {
            Dropdown d = new Dropdown();
            d.kind = "FREE";
            return d;
        }
    }

    private ColumnDef() {}

    private static ColumnDef of(String name, String label, String type, String role, boolean editable) {
        ColumnDef c = new ColumnDef();
        c.name = name; c.label = label; c.type = type; c.role = role; c.editable = editable;
        return c;
    }

    /** 子维度编码列 + 主表下拉（选中带出 nameColumn 只读名称）。 */
    public static ColumnDef subDimMaster(String name, String label, String master, String nameColumn) {
        ColumnDef c = of(name, label, "STRING", "SUBDIM", true);
        c.dropdown = Dropdown.master(master, nameColumn);
        return c;
    }

    /** 编码/名称类子维度列，无主表 → 自由文本（要素名称 / 模具编号 / 物料BOM组成件，C13）。 */
    public static ColumnDef subDimFree(String name, String label, String type) {
        ColumnDef c = of(name, label, type, "SUBDIM", true);
        c.dropdown = Dropdown.free();
        return c;
    }

    /** 只读名称列（关联主表带出，不入 content、不进指纹比对）。 */
    public static ColumnDef nameCol(String name, String label) {
        return of(name, label, "STRING", "NAME", false);
    }

    /**
     * 只读子维度列：值不可改但需随行回传（分组标识）。
     * 用于 ELEMENT_BOM 合并展示时的 material_part_no —— 决定行属于哪个材质料号版本组，前端只读展示、原样回传。
     */
    public static ColumnDef subDimReadonly(String name, String label) {
        return of(name, label, "STRING", "SUBDIM", false);
    }

    /** 普通可编辑值列。 */
    public static ColumnDef value(String name, String label, String type) {
        return of(name, label, type, "VALUE", true);
    }

    /** 固定枚举值列（无字典表，未知可输入回退）。 */
    public static ColumnDef valueEnum(String name, String label, List<String> options) {
        ColumnDef c = of(name, label, "ENUM", "VALUE", true);
        c.dropdown = Dropdown.enumOf(options);
        return c;
    }
}
