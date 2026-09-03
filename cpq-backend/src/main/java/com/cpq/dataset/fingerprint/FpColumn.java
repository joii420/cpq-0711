package com.cpq.dataset.fingerprint;

/**
 * 参与行指纹的单列描述（task-260902 · B-4）。
 *
 * <p>刻意<b>不</b>直接用 {@code com.cpq.dataset.registry.ColumnDef}：指纹算法是纯函数，
 * 与 Registry 的字段形态解耦后可脱离 CDI 单测（AC-16/17/18 的等价性用例）。
 * Registry → FpColumn 的转换只发生在 {@link RowFingerprintCalculator} 一处。
 *
 * @param name  DB 列名（从行 Map 取值用的 key）
 * @param type  Registry 声明的列类型
 * @param scale DB 列 scale（numeric(p,s) 的 s），未知传 null
 */
public record FpColumn(String name, String type, Integer scale) {
}
