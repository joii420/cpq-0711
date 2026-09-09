package com.cpq.task260909;

import com.cpq.common.security.RoleAllowed;
import com.cpq.component.resource.CostingBomTreeConfigResource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260909 B-8（AC-18，用户裁决 D-5）—— 「核价树配置」端点收紧为管理员专属的<b>结构断言</b>。
 *
 * <p>为什么用结构断言而不是发 HTTP：权限缺口的形态是「某个方法上多了一条方法级注解，
 * 于是<b>那一个</b>端点仍对 SALES_MANAGER 开着」——按端点逐个发请求的用例会漏掉将来<b>新增</b>的方法，
 * 而本用例对<b>全部</b>公开端点方法一次性设防，加新方法也跑不掉。
 * （403/200 的真实链路验证由主线用 SALES_MANAGER 账号在真机做，两者互补不互替。）
 */
class CostingBomTreeConfigRoleTest {

    @Test
    void classLevelRoleIsSystemAdminOnly() {
        RoleAllowed ra = CostingBomTreeConfigResource.class.getAnnotation(RoleAllowed.class);
        assertNotNull(ra, "类级 @RoleAllowed 不能被删掉——删了就是完全不鉴权");
        assertArrayEquals(new String[]{"SYSTEM_ADMIN"}, ra.value(),
                "实得 " + Arrays.toString(ra.value()) + "；SALES_MANAGER 必须已被移除（AC-18）");
    }

    /**
     * 🚫 不许出现方法级 {@code @RoleAllowed}：它会<b>覆盖</b>类级注解，等于给"哪些端点归谁"
     * 开第二个事实来源 —— 漏一个方法就是一个静默的权限缺口。
     */
    @Test
    void noMethodLevelRoleAnnotationOverridesTheClassLevelOne() {
        for (Method m : CostingBomTreeConfigResource.class.getDeclaredMethods()) {
            if (m.isSynthetic()) continue;
            assertNull(m.getAnnotation(RoleAllowed.class),
                    "方法 " + m.getName() + " 上出现了方法级 @RoleAllowed，会覆盖类级 {SYSTEM_ADMIN}");
        }
    }
}
