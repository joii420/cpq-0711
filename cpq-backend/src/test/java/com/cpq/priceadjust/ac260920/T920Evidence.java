package com.cpq.priceadjust.ac260920;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 证据落盘（testing.md §2 / §5.7⑤）：默认 {@code TASK/证据/测试/S-1/<run-时间戳>/<AC>.txt}，
 * 根目录可用 {@code -Dt260920.evidenceDir=…}、轮次名可用 {@code -Dt260920.runLabel=…} 改写 ⇒ 复跑 / 证伪实验不覆盖上一轮。
 */
public final class T920Evidence {

    public static final String RUN_LABEL = System.getProperty("t260920.runLabel",
        "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

    private T920Evidence() {
    }

    public static Path root() {
        String p = System.getProperty("t260920.evidenceDir");
        if (p == null || p.isBlank()) {
            return Paths.get("..", "dev-docs", "task-260729-客户价格调整策略和价格版本",
                "task-260920-审核列表秒开与按需试算", "证据", "测试", "S-1").toAbsolutePath().normalize();
        }
        return Paths.get(p).toAbsolutePath().normalize();
    }

    public static void log(String ac, String content) {
        String line = "[" + LocalDateTime.now() + "] " + content + System.lineSeparator();
        System.out.print("[T260920-EVIDENCE " + ac + "] " + line);
        try {
            Path dir = root().resolve(RUN_LABEL);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ac + ".txt"), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[T260920-EVIDENCE] 写证据文件失败: " + e);
        }
    }
}
