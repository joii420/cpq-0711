package com.cpq.component;

import com.cpq.component.service.ComponentDriverService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** default-source BASIC_DATA: viewBasePath / parseBasicDataDefaultViewBases 纯函数单测。 */
class ComponentDriverServiceHelpersTest {

    private static Object invoke(String name, Class<?> argType, Object arg) throws Exception {
        Method m = ComponentDriverService.class.getDeclaredMethod(name, argType);
        m.setAccessible(true);
        return m.invoke(null, arg);
    }

    @Test
    void viewBasePath_stripsChineseLeafAndBraces() throws Exception {
        assertEquals("$cp_view", invoke("viewBasePath", String.class, "$cp_view.品名"));
        assertEquals("$cp_view", invoke("viewBasePath", String.class, "{$cp_view.品名}"));
        assertEquals("$cp_view[a=1]", invoke("viewBasePath", String.class, "$cp_view[a=1].规格"));
        assertNull(invoke("viewBasePath", String.class, "mat_part.unit_weight"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseBasicDataDefaultViewBases_dedupesByView() throws Exception {
        String fields = "[" +
            "{\"name\":\"品名\",\"field_type\":\"INPUT_TEXT\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$cp_view.品名\"}}," +
            "{\"name\":\"规格\",\"field_type\":\"INPUT_TEXT\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$cp_view.规格\"}}," +
            "{\"name\":\"汇率\",\"field_type\":\"INPUT_NUMBER\",\"default_source\":{\"type\":\"BNF_PATH\",\"path\":\"$x.y\"}}" +
            "]";
        List<String> bases = (List<String>) invoke("parseBasicDataDefaultViewBases", String.class, fields);
        assertEquals(List.of("$cp_view"), bases);
    }
}
