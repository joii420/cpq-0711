package com.cpq.quotation.service.rowkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数行键冲突检测：给定某组件按行序排列的 rowKey 列表，
 * 返回出现 ≥2 次的 key 及其全部行下标。空白 key 跳过（无法计算行键的行不参与判定）。
 */
public final class RowKeyConflictDetector {

    private RowKeyConflictDetector() {}

    public static List<RowKeyConflict> detect(String componentName, List<String> rowKeys) {
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < rowKeys.size(); i++) {
            String k = rowKeys.get(i);
            if (k == null || k.isBlank()) continue;
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }
        List<RowKeyConflict> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
            if (e.getValue().size() >= 2) {
                out.add(new RowKeyConflict(componentName, e.getKey(), List.copyOf(e.getValue())));
            }
        }
        return out;
    }
}
