package com.cpq.dataset.registry;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三套 Registry 的按 {@code {dataset}} 路径参数分流入口（api.md §0：{@code quote} / {@code cost-basic} / {@code cost-detail}）。
 *
 * <p>导入端点、维护端点共用同一套实现，靠本类分流；非法值返回 null → Resource 层转 404。
 */
@ApplicationScoped
public class DatasetRegistries {

    @Inject QuoteRegistry quote;
    @Inject CostBasicRegistry costBasic;
    @Inject CostDetailRegistry costDetail;

    private final Map<String, DatasetRegistry> byKey = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        for (DatasetRegistry r : List.of(quote, costBasic, costDetail)) {
            if (byKey.putIfAbsent(r.datasetKey(), r) != null) {
                throw new IllegalStateException("重复 datasetKey: " + r.datasetKey());
            }
        }
    }

    /** @return null 表示 {@code {dataset}} 非法（Resource 层应转 404）。 */
    public DatasetRegistry byKey(String datasetKey) {
        return datasetKey == null ? null : byKey.get(datasetKey);
    }

    public List<DatasetRegistry> all() {
        return List.copyOf(byKey.values());
    }
}
