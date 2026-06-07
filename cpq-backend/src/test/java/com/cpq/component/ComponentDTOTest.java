package com.cpq.component;

import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.entity.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComponentDTO.from 映射回显验证（修复"行键保存后切回不回显"bug）。
 * 根因：getById/create/update 返回 ComponentDTO，但 DTO 原先没有 rowKeyFields 字段
 * 也没在 from() 解析 → 前端 loaded.rowKeyFields 永远 undefined → 勾选回显不出来。
 * 纯 JUnit（不需 DB/auth）验证 entity JSON 字符串 → DTO List 的映射。
 */
class ComponentDTOTest {

    @Test
    void from_parsesRowKeyFieldsJsonToList() {
        Component c = new Component();
        c.rowKeyFields = "[\"子件\",\"元素\"]";
        ComponentDTO dto = ComponentDTO.from(c);
        assertEquals(List.of("子件", "元素"), dto.rowKeyFields,
            "rowKeyFields JSON 字符串必须解析为 List 返回给前端回显");
    }

    @Test
    void from_nullRowKeyFields_yieldsNull() {
        Component c = new Component();
        c.rowKeyFields = null;
        ComponentDTO dto = ComponentDTO.from(c);
        assertNull(dto.rowKeyFields, "未配置行键时返回 null（前端按 [] 处理）");
    }

    @Test
    void from_blankRowKeyFields_yieldsNull() {
        Component c = new Component();
        c.rowKeyFields = "";
        ComponentDTO dto = ComponentDTO.from(c);
        assertNull(dto.rowKeyFields);
    }

    @Test
    void from_maps_bomRecursiveExpand_true() {
        Component c = new Component();
        c.bomRecursiveExpand = true;
        ComponentDTO dto = ComponentDTO.from(c);
        assertEquals(Boolean.TRUE, dto.bomRecursiveExpand);
    }

    @Test
    void from_maps_bomRecursiveExpand_false() {
        Component c = new Component();
        c.bomRecursiveExpand = false;
        ComponentDTO dto = ComponentDTO.from(c);
        assertEquals(Boolean.FALSE, dto.bomRecursiveExpand);
    }
}
