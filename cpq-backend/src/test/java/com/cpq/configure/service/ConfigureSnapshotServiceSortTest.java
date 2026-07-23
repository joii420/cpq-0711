package com.cpq.configure.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.configure.service.ConfigureSnapshotService.DriverComp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0722：组件级 sort_field 行排序 helper 单元测试（纯静态方法，不起 Quarkus）。
 */
class ConfigureSnapshotServiceSortTest {

    private static ExpandDriverResponse.Row row(Object seq) {
        ExpandDriverResponse.Row r = new ExpandDriverResponse.Row();
        r.driverRow = new LinkedHashMap<>();
        r.driverRow.put("_项次", seq);
        return r;
    }

    private static DriverComp comp(String sortField, String tabType) {
        DriverComp c = new DriverComp();
        c.sortField = sortField;
        c.tabType = tabType;
        c.fields = "[{\"name\":\"项次\",\"default_source\":{\"path\":\"$qt_view._项次\",\"type\":\"BASIC_DATA\"}}]";
        return c;
    }

    @Test
    void compareNumericAware_sortsNumbersNumerically() {
        assertTrue(ConfigureSnapshotService.compareNumericAware(2, 10) < 0);   // 数字序: 2 < 10
        assertTrue(ConfigureSnapshotService.compareNumericAware("2", "10") < 0); // 文本数字也走数字序
        assertTrue(ConfigureSnapshotService.compareNumericAware(10, 2) > 0);
        assertEquals(0, ConfigureSnapshotService.compareNumericAware(3, 3));
    }

    @Test
    void compareNumericAware_nullsLast() {
        assertTrue(ConfigureSnapshotService.compareNumericAware(null, 1) > 0); // null 殿后
        assertTrue(ConfigureSnapshotService.compareNumericAware(1, null) < 0);
        assertEquals(0, ConfigureSnapshotService.compareNumericAware(null, null));
    }

    @Test
    void resolveDriverColumn_takesLastSegmentOfPath() {
        String fields = "[{\"name\":\"项次\",\"default_source\":{\"path\":\"$qt_view._项次\"}}]";
        assertEquals("_项次", ConfigureSnapshotService.resolveDriverColumn("项次", fields));
        assertNull(ConfigureSnapshotService.resolveDriverColumn("不存在", fields));
        assertNull(ConfigureSnapshotService.resolveDriverColumn("项次", "[]"));
    }

    @Test
    void resolveDriverColumn_fallsBackToBasicDataPath() {
        String fields = "[{\"name\":\"序号\",\"basic_data_path\":\"$ys_view._序号\"}]";
        assertEquals("_序号", ConfigureSnapshotService.resolveDriverColumn("序号", fields));
    }

    @Test
    void sortRowsBySortField_ascendingNumeric() {
        List<ExpandDriverResponse.Row> rows = new ArrayList<>(List.of(row(4), row(3), row(2), row(1)));
        List<ExpandDriverResponse.Row> sorted = ConfigureSnapshotService.sortRowsBySortField(rows, comp("项次", null));
        assertEquals(List.of("1", "2", "3", "4"),
                sorted.stream().map(r -> String.valueOf(r.driverRow.get("_项次"))).toList());
        // 不改原 list（AP-37 缓存共享面保护）
        assertEquals("4", String.valueOf(rows.get(0).driverRow.get("_项次")));
    }

    @Test
    void sortRowsBySortField_noSortFieldReturnsAsIs() {
        List<ExpandDriverResponse.Row> rows = new ArrayList<>(List.of(row(4), row(1)));
        assertSame(rows, ConfigureSnapshotService.sortRowsBySortField(rows, comp(null, null)));
        assertSame(rows, ConfigureSnapshotService.sortRowsBySortField(rows, comp("  ", null)));
    }

    @Test
    void sortRowsBySortField_treeTabNotSorted() {
        List<ExpandDriverResponse.Row> rows = new ArrayList<>(List.of(row(4), row(1)));
        // tabType=BOM → 树序为准，不排
        assertSame(rows, ConfigureSnapshotService.sortRowsBySortField(rows, comp("项次", "BOM")));
    }
}
