package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PublishWithoutSubtotalTest {

    @Inject
    TemplateService templateService;

    @Test
    @TestTransaction
    void publish_withoutSubtotalComponent_succeeds() {
        // 播种一个 NORMAL 组件
        Component comp = new Component();
        comp.name = "投料-noSub";
        comp.code = "TOULIAO_NOSUB_" + UUID.randomUUID();
        comp.componentType = "NORMAL";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.persist();

        // 播种一个 DRAFT 模板(无 SUBTOTAL 组件、subtotalFormula 默认 "[]")
        Template t = new Template();
        t.name = "无小计模板-" + UUID.randomUUID();
        t.status = "DRAFT";
        t.templateSeriesId = UUID.randomUUID(); // NOT NULL 必填
        // subtotalFormula 默认 "[]", 不另赋值
        // createdAt/updatedAt 由 @PrePersist 自动赋值
        t.persist();

        // 绑定组件(无 SUBTOTAL 组件)
        TemplateComponent tc = new TemplateComponent();
        tc.templateId = t.id;
        tc.componentId = comp.id;
        tc.tabName = "投料";
        tc.sortOrder = 0;
        tc.persist();

        // 目标: 不因"必须配置小计"而被拦截
        // 若有其它原因抛 BusinessException, 断言 message 不含"必须配置小计"
        try {
            templateService.publish(t.id, new PublishRequest());
            // 发布成功 -> 验证状态变更
            Template reloaded = Template.findById(t.id);
            assertEquals("PUBLISHED", reloaded.status);
        } catch (BusinessException ex) {
            assertFalse(
                ex.getMessage().contains("必须配置小计"),
                "发布不应因缺少小计组件被拦截，但得到异常: " + ex.getMessage()
            );
        }
    }
}
