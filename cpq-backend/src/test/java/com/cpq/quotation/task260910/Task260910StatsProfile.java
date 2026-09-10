package com.cpq.quotation.task260910;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * task-260910 · AC-7 专用 profile：打开 Hibernate statistics，才能按 SQL 文本统计执行次数。
 *
 * <p>不改 {@code application-test.properties} —— 那是全体测试共用的文件，
 * 打开统计会给所有测试加常驻开销。
 */
public class Task260910StatsProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.hibernate-orm.statistics", "true");
    }
}
