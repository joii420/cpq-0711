package com.cpq.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** 与前端 condTree.test.ts 同读对账样本，逐例真值一致。 */
class CondTreeEvaluatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TestFactory
    List<DynamicTest> reconcile() throws Exception {
        Path fixture = Path.of("..", "cpq-frontend", "src", "utils", "__fixtures__", "condtree-cases.json");
        JsonNode root = M.readTree(Files.readString(fixture));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            String name = c.path("name").asText("");
            tests.add(dynamicTest(name, () -> {
                JsonNode values = c.path("values");
                boolean actual = CondTreeEvaluator.eval(c.path("tree"), col -> {
                    JsonNode v = values.path(col);
                    if (v.isMissingNode() || v.isNull()) return null;
                    return v.isNumber() ? (Object) v.numberValue() : v.asText();
                });
                assertEquals(c.path("expected").asBoolean(), actual, "条件树漂移: " + name);
            }));
        }
        return tests;
    }
}
