package com.cpq.formula.dataloader;

import com.cpq.datasource.sqlview.QuotePendingScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0725 T2 — {@link DataLoader#scopedCacheKey(String)} 单测（纯函数，无 DB）。
 *
 * <p>背景：{@code DataLoader} 是 {@code @RequestScoped}，实例级 {@code resultCache} 只在
 * {@code @PreDestroy} 清，且就在 driver 展开路径上（{@code ComponentDriverService:498/:581/:678}）。
 * 其 3 个进缓存的 {@code loadByPath} 重载（1-arg {@code $view} 分支 / 1-arg 非视图分支 / 5-arg
 * {@code $view} 分支）原本 key 均不含「pending 是否可见」维度，同一报价单内报价侧与核价侧共用
 * 同一 {@code DataLoader} 实例时会跨侧串号（AP-37 型，见 backtask T2 评审补充 1）。
 *
 * <p>三处分支已统一委托 {@link DataLoader#scopedCacheKey(String)}（package-private 静态方法），
 * 故本测试只需验证该单一入口的行为，即覆盖三处调用点的 key 差异性——测试策略见 T2 交付说明
 * 「DataLoader 三个重载分别怎么补的维度」。
 */
class DataLoaderScopedCacheKeyTest {

    @AfterEach
    void resetScope() {
        QuotePendingScope.restore(null);
    }

    @Test
    void scopedCacheKey_scopeClosed_matchesRawKeyExactly() {
        assertEquals("rawKey", DataLoader.scopedCacheKey("rawKey"),
                "关闭态 QuotePendingScope.cacheTag()==\"\" ⟹ scopedCacheKey 必须与改动前(裸 key)逐字相同");
    }

    @Test
    void scopedCacheKey_complexCompositeKey_scopeClosed_matchesRawKeyExactly() {
        // 模拟 5-arg 重载的组合 key（normalizedPath::partNo::customerId::viewLineItemId::ownerTag::qid）
        String composite = "$mc_view::P1::" + UUID.randomUUID() + "::null::null/null::" + UUID.randomUUID();
        assertEquals(composite, DataLoader.scopedCacheKey(composite));
    }

    @Test
    void scopedCacheKey_scopeOpenVsClosed_differ() {
        String closed = DataLoader.scopedCacheKey("$mc_view");
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            String open = DataLoader.scopedCacheKey("$mc_view");
            assertNotEquals(closed, open,
                    "scope 开/关下三个 loadByPath 缓存分支共用的 key 必须不同，否则核价侧会命中报价侧" +
                            "改写后的缓存条目（AP-37 型跨侧串号）");
            assertTrue(open.startsWith("$mc_view"), "原始 key 前缀必须原样保留，不影响其余既有维度");
            assertTrue(open.contains(qid.toString().replace("-", "")));
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void scopedCacheKey_frozenScope_stillMatchesClosedFormat() {
        // open() 内建冻结判定：SUBMITTED 状态下 open() 已存 null，cacheTag()=="" ⟹
        // scopedCacheKey 应与完全未打开时逐字相同（核价侧/冻结态零回归延伸到缓存维度）。
        String closed = DataLoader.scopedCacheKey("$mc_view");
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "SUBMITTED");
        try {
            assertEquals(closed, DataLoader.scopedCacheKey("$mc_view"));
        } finally {
            QuotePendingScope.restore(prev);
        }
    }
}
