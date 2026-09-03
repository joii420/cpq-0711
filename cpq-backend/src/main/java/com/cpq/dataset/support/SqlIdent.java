package com.cpq.dataset.support;

/**
 * SQL 标识符白名单（防注入）。表名/列名一律经本类校验后才允许拼进 SQL，
 * 值一律走命名参数绑定 —— 与既有 {@code VersionedV6Writer.safeIdent} 同纪律。
 */
public final class SqlIdent {
    private SqlIdent() {}

    public static String of(String id) {
        if (id == null || !id.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + id);
        }
        return id;
    }
}
