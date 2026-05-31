package com.cpq.datasource.sqlview;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试用: 线程级 SQL 捕获上下文。
 *
 * <p>仅当调用方显式 {@link #begin()} 后, {@link SqlViewExecutor} 才会把每条改写后的最终 SQL
 * (含 {@code ?} 占位符) + 参数记录进当前线程的缓冲区; 调用方用 {@link #drain()} 取走并清理。
 *
 * <p>用途: 报价单渲染时把「第一个产品各 Tab 的 driver 执行 SQL」回传前端 console 打印。
 * 默认不开启 (begin 未调用 → isActive()=false → 零开销, 不影响生产路径)。
 *
 * <p>注意: 基于 ThreadLocal, 要求 begin()/record()/drain() 在同一线程内成对使用。
 * 报价单 batch-expand 在请求线程内顺序调用 expand(), 满足该约束。
 */
public final class SqlDebugContext {

    private static final ThreadLocal<List<String>> BUFFER = new ThreadLocal<>();

    private SqlDebugContext() {}

    /** 开启当前线程的 SQL 捕获 (清空旧缓冲)。 */
    public static void begin() {
        BUFFER.set(new ArrayList<>());
    }

    /** 是否处于捕获状态 (用于 expand 旁路缓存 / SqlViewExecutor 决定是否记录)。 */
    public static boolean isActive() {
        return BUFFER.get() != null;
    }

    /** SqlViewExecutor 在执行前调用: 记录改写后的最终 SQL + 参数。 */
    public static void record(String sql, List<Object> params) {
        List<String> buf = BUFFER.get();
        if (buf == null) return;
        StringBuilder sb = new StringBuilder(sql == null ? "" : sql.trim());
        if (params != null && !params.isEmpty()) {
            sb.append("\n-- params: ").append(params);
        }
        buf.add(sb.toString());
    }

    /** 取走当前线程捕获到的所有 SQL 并清理 ThreadLocal。 */
    public static List<String> drain() {
        List<String> buf = BUFFER.get();
        BUFFER.remove();
        return buf == null ? List.of() : buf;
    }

    /** 取走并拼成单个字符串 (多条 SQL 用分隔线连接), 便于直接放进响应字段。 */
    public static String drainJoined() {
        List<String> buf = drain();
        if (buf.isEmpty()) return null;
        return String.join("\n--------\n", buf);
    }
}
