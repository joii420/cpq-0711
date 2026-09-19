package com.cpq.priceadjust.ac260918;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 证据落盘（testing.md §2「证据必须留得下来」+ §5.7⑤「复跑不得覆盖留档证据」）。
 *
 * <ul>
 *   <li>根目录可由 {@code -Drp0918a.evidenceDir=…} 或环境变量 {@code RP0918A_EVIDENCE_DIR} 改写；
 *       默认 = 任务目录 {@code 证据/测试/S-BE/}（相对 {@code cpq-backend/} 工作目录解析）；</li>
 *   <li>每轮运行一个子目录 {@code <runLabel>/}，默认 {@code run-yyyyMMdd-HHmmss}，可由
 *       {@code -Drp0918a.runLabel=…} 指定 ⇒ 复跑 / 证伪实验天然写到新目录，不覆盖上一轮；</li>
 *   <li>同时打印到 stdout，surefire 报告里也留一份。</li>
 * </ul>
 */
public final class Rp0918aEvidence {

    public static final String RUN_LABEL = System.getProperty("rp0918a.runLabel",
        "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

    private Rp0918aEvidence() {
    }

    public static Path root() {
        String p = System.getProperty("rp0918a.evidenceDir");
        if (p == null || p.isBlank()) p = System.getenv("RP0918A_EVIDENCE_DIR");
        if (p == null || p.isBlank()) {
            return Paths.get("..", "dev-docs", "task-260729-客户价格调整策略和价格版本",
                "repair-260918-大单升版超时与涨跌率显示", "证据", "测试", "S-BE").toAbsolutePath().normalize();
        }
        return Paths.get(p).toAbsolutePath().normalize();
    }

    public static Path runDir() {
        return root().resolve(RUN_LABEL);
    }

    /** 追加写入 {@code <runDir>/<ac>.txt}，每条带时间戳。 */
    public static void log(String ac, String content) {
        String line = "[" + LocalDateTime.now() + "] " + content + System.lineSeparator();
        System.out.print("[RP0918A-EVIDENCE " + ac + "] " + line);
        try {
            Path dir = runDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ac + ".txt"), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[RP0918A-EVIDENCE] 写证据文件失败: " + e);
        }
    }

    /** 整份写入（覆盖同名文件，仅用于本轮目录内或调用方明确指定的稳定文件）。 */
    public static Path writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            System.out.println("[RP0918A-EVIDENCE] 写入 " + file);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("写证据文件失败: " + file, e);
        }
    }
}
