package com.cpq.priceadjust.service;

import com.cpq.common.PrecisionHttpContractSupport;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.entity.PriceAdjustSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0806 T6 · {@code price_adjust_settings.subtotal_guard_enabled} 开关（FR-9 / D-5 / api.md §1-2）。
 *
 * <p>🔒 单行表是全局共享状态（测试库 {@code cpq_db}，与开发库 {@code cpq_db_0724} 隔离，见
 * CLAUDE.md profile 表），但仍可能被同库并发跑的其它测试撞见——每个测试前后各存/还原一次原始值
 * （记忆 {@code cpq-psql-config-save-restore-pitfall}），不留篡改。
 *
 * <p>核心断言：api.md §2「实现陷阱」—— PUT 请求体两字段各自独立，{@code null} = 不改动该项，
 * 不是"置默认值"。只提交阈值不应把开关冲掉，反之亦然。
 */
@QuarkusTest
@TestProfile(PriceAdjustSettingsServiceTest.RbacOffProfile.class)
class PriceAdjustSettingsServiceTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    PriceAdjustSettingsService service;
    @Inject
    EntityManager em;
    @Inject
    ObjectMapper mapper;
    @InjectMock
    SessionHelper sessionHelper;

    private BigDecimal originalThreshold;
    private boolean originalEnabled;

    @BeforeEach
    @Transactional
    void snapshot() {
        org.mockito.Mockito.when(sessionHelper.getCurrentUserId(org.mockito.ArgumentMatchers.any()))
            .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        assertNotNull(s, "V378 已种子化单行配置，理论上恒非 null");
        originalThreshold = s.subtotalGuardThreshold;
        originalEnabled = s.subtotalGuardEnabled;
    }

    @AfterEach
    @Transactional
    void restore() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s != null) {
            s.subtotalGuardThreshold = originalThreshold;
            s.subtotalGuardEnabled = originalEnabled;
            s.persist();
        }
    }

    @Test
    @Transactional
    void put_onlyThreshold_doesNotResetEnabled() {
        // 先把开关显式置 true，制造一个"非默认值"，才能验证后续只改阈值不会把它冲回去
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardEnabled = true;
        seed.subtotalGuardThreshold = new BigDecimal("0.02");
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardThreshold = new BigDecimal("123.45");
        // subtotalGuardEnabled 故意不设（null）—— 契约：null = 不改
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("123.45").compareTo(resp.subtotalGuardThreshold), "阈值应按请求更新");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "只提交阈值不应把开关冲回默认值/改动");
        assertTrue(service.isSubtotalGuardEnabled(), "读库口径同样应保持 true（不缓存，读库为准）");
    }

    @Test
    @Transactional
    void put_onlyEnabled_doesNotResetThreshold() {
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardThreshold = new BigDecimal("77.7");
        seed.subtotalGuardEnabled = false;
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardEnabled = true;
        // subtotalGuardThreshold 故意不设（null）—— 契约：null = 不改
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("77.7").compareTo(resp.subtotalGuardThreshold),
            "只提交开关不应把阈值冲回默认值/改动");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "开关应按请求更新");
    }

    @Test
    @Transactional
    void put_bothNull_isNoOp() {
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardThreshold = new BigDecimal("5.5");
        seed.subtotalGuardEnabled = true;
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO(); // 两字段都 null
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("5.5").compareTo(resp.subtotalGuardThreshold), "两字段都不提交，阈值应原样保留");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "两字段都不提交，开关应原样保留");
    }

    @Test
    @Transactional
    void get_reflectsEnabledFlag_immediately_noRestart() {
        PriceAdjustSettingsDTO on = new PriceAdjustSettingsDTO();
        on.subtotalGuardEnabled = true;
        service.put(on, UUID.randomUUID());
        assertEquals(Boolean.TRUE, service.get().subtotalGuardEnabled);
        assertTrue(service.isSubtotalGuardEnabled());

        PriceAdjustSettingsDTO off = new PriceAdjustSettingsDTO();
        off.subtotalGuardEnabled = false;
        service.put(off, UUID.randomUUID());
        assertEquals(Boolean.FALSE, service.get().subtotalGuardEnabled);
        assertFalse(service.isSubtotalGuardEnabled());
    }

    @Test
    void tc052_thresholdDecimalString_roundTripsAtExistingSixDigitScale() throws Exception {
        String expectedText = "12345678901234.123456";
        Response saved = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{\"subtotalGuardThreshold\":\"" + expectedText + "\"}")
            .put("/api/cpq/price-adjust/settings");
        assertEquals(200, saved.statusCode(), saved.asString());
        JsonNode savedJson = mapper.readTree(saved.asString());
        assertTrue(savedJson.path("subtotalGuardThreshold").isTextual(), saved.asString());
        assertEquals(expectedText, savedJson.path("subtotalGuardThreshold").asText());

        SettingsFingerprint stored = readSettingsFingerprint();
        assertEquals(0, new BigDecimal(expectedText).compareTo(stored.threshold()),
            "TC-052 threshold persistence must preserve all six business decimals");
        assertEquals(6, stored.threshold().scale(),
            "TC-052 stored threshold keeps the existing scale=6 contract");

        Response reopened = RestAssured.given().get("/api/cpq/price-adjust/settings");
        assertEquals(200, reopened.statusCode(), reopened.asString());
        JsonNode reopenedJson = mapper.readTree(reopened.asString());
        assertTrue(reopenedJson.path("subtotalGuardThreshold").isTextual(), reopened.asString());
        assertEquals(expectedText, reopenedJson.path("subtotalGuardThreshold").asText());

        Object[] schema = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
            "SELECT numeric_precision,numeric_scale FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='price_adjust_settings' "
                + "AND column_name='subtotal_guard_threshold'").getSingleResult());
        assertEquals(20, ((Number) schema[0]).intValue(), "TC-052 existing DB precision");
        assertEquals(6, ((Number) schema[1]).intValue(), "TC-052 existing DB scale");

        Column mapping = PriceAdjustSettings.class.getDeclaredField("subtotalGuardThreshold")
            .getAnnotation(Column.class);
        assertNotNull(mapping);
        assertEquals(20, mapping.precision(), "TC-052 existing JPA precision");
        assertEquals(6, mapping.scale(), "TC-052 existing JPA scale");

        String rejectedNumeric = "12345678901234.123455";
        SettingsFingerprint beforeRejected = readSettingsFingerprint();
        Response rejected = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{\"subtotalGuardThreshold\":" + rejectedNumeric + "}")
            .put("/api/cpq/price-adjust/settings");
        SettingsFingerprint afterRejected = readSettingsFingerprint();

        assertAll(
            () -> PrecisionHttpContractSupport.assertBadRequest(
                rejected, "subtotalGuardThreshold", rejectedNumeric),
            () -> assertEquals(beforeRejected.xmin(), afterRejected.xmin(),
                "TC-052 rejected number must not update the singleton row"),
            () -> assertEquals(beforeRejected.updatedAt(), afterRejected.updatedAt(),
                "TC-052 rejected number must not change updated_at"),
            () -> assertEquals(0, beforeRejected.threshold().compareTo(afterRejected.threshold()),
                "TC-052 rejected number must not change the threshold value"));
    }

    private SettingsFingerprint readSettingsFingerprint() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT subtotal_guard_threshold,xmin::text,updated_at::text "
                    + "FROM price_adjust_settings WHERE id=1")
                .getSingleResult();
            return new SettingsFingerprint(
                (BigDecimal) row[0], String.valueOf(row[1]), String.valueOf(row[2]));
        });
    }

    private record SettingsFingerprint(BigDecimal threshold, String xmin, String updatedAt) {
    }

    @Test
    @Transactional
    void put_negativeThreshold_rejected_evenWhenOnlyEnabledIntended() {
        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardThreshold = new BigDecimal("-1");
        req.subtotalGuardEnabled = true;
        assertThrows(com.cpq.common.exception.BusinessException.class, () -> service.put(req, UUID.randomUUID()),
            "负阈值必须拒绝，既有校验不因新增字段而失效");
    }
}
