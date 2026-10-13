package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialImportReportDTO;
import com.cpq.configure.dto.MaterialImportReportDTO.CreatedConfig;
import com.cpq.configure.dto.MaterialImportReportDTO.SkippedRow;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeComposition;
import com.cpq.configure.entity.MaterialRecipeConfig;
import com.cpq.configure.entity.MaterialRecipeElement;
import com.cpq.configure.exception.MaterialRecipeApiException;
import com.cpq.configure.rules.MaterialRecipeNumbering;
import com.cpq.configure.rules.MaterialRecipeRules;
import com.cpq.configure.service.MaterialRecipeConfigService.ElementRef;
import com.cpq.configure.service.MaterialRecipeConfigService.ResolvedPct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 材质库导入服务（task-0708 · B4/B5 → <b>task-260901 · B-8~B-13 整体重写</b>）。
 *
 * <p><b>新语义</b>（需求文档 §③ / api.md §2.3）：
 * <ol>
 *   <li><b>单表 4 列</b>：材质 / 组号 / 元素符号 / 含量。读第一个工作表、按表头识别，<b>不依赖 sheet 名</b>；
 *       命中旧两 sheet 结构 → 400 {@code IMPORT_TEMPLATE_OUTDATED}；表头不符 → 400 {@code IMPORT_HEADER_INVALID}；
 *       表头对但零数据行 → 200 + 全 0 报告（AC-23）。</li>
 *   <li><b>两种编号全自动</b>：材质编号按五位补零自增（B-6）；元素必须先在元素主表维护，材质导入绝不写元素表。</li>
 *   <li><b>只增不改</b>：按材质名匹配材质（M-6 防重名），按<b>内容</b>与 ACTIVE 配置逐值比对（M-4）；
 *       不存在才新增配置。🚫 不再有「整体重灌覆盖」，也<b>不复活 INACTIVE 配置</b>（M-3）。</li>
 *   <li><b>组号只在 Excel 内分组</b>，不原样落库；真正新增配置使用数据库历史最大组号+1。</li>
 * </ol>
 *
 * <p><b>N+1 纪律</b>：全流程只有常数条 SQL —— 材质按名 IN 一次、元素组成 IN 一次、
 * ACTIVE 配置与其元素各 IN 一次、element 主表两次（现有符号 + 编号水位）、材质编号水位一次；
 * 写侧走 Hibernate JDBC batch。循环体内<b>没有任何查询</b>。
 */
@ApplicationScoped
public class MaterialRecipeImportService {

    /** 模板生成用的 sheet 名；导入侧<b>不依赖</b>它（读第一个工作表）。 */
    static final String SHEET_NAME = "材质含量";

    /** 旧两 sheet 模板的特征名（命中即拒收，AC-11）。 */
    static final Set<String> LEGACY_SHEET_NAMES = Set.of("材质编号", "材质对应元素");

    static final List<String> HEADER = List.of("材质", "组号", "元素符号", "含量");

    private static final BigDecimal HUNDRED = MaterialRecipeRules.HUNDRED;

    /**
     * 元素符号→中文字典（与 V317 seed 同源；导入遇未知符号回退=符号）。
     * R1（2026-07-09）：数字牌号 304/316/301/430 是合法钢牌号组成项，补中文名；
     * 191/206/223/258/721 含义不明暂留符号（回退=符号）。
     */
    private static final Map<String, String> DICT = Map.ofEntries(
        Map.entry("Ag", "银"), Map.entry("Cu", "铜"), Map.entry("Ni", "镍"),
        Map.entry("Al", "铝"), Map.entry("Fe", "铁"), Map.entry("Sn", "锡"),
        Map.entry("Zn", "锌"), Map.entry("Cr", "铬"), Map.entry("Mn", "锰"),
        Map.entry("Si", "硅"), Map.entry("P", "磷"), Map.entry("C", "碳"),
        Map.entry("Be", "铍"), Map.entry("Cd", "镉"), Map.entry("Ce", "铈"),
        Map.entry("In", "铟"), Map.entry("Ir", "铱"), Map.entry("Pt", "铂"),
        Map.entry("Pd", "钯"), Map.entry("W", "钨"), Map.entry("Au", "金"),
        Map.entry("SnO2", "二氧化锡"), Map.entry("ZnO", "氧化锌"), Map.entry("CdO", "氧化镉"),
        Map.entry("WC", "碳化钨"), Map.entry("H70", "黄铜H70"), Map.entry("DC04", "冷轧钢DC04"),
        Map.entry("Ni36", "铁镍合金Ni36"), Map.entry("Ni42", "铁镍合金Ni42"), Map.entry("不锈钢", "不锈钢"),
        Map.entry("304", "304不锈钢"), Map.entry("316", "316不锈钢"),
        Map.entry("301", "301不锈钢"), Map.entry("430", "430不锈钢"));

    @Inject
    EntityManager em;

    @Inject
    MaterialRecipeConfigService configService;

    @Inject
    MaterialRecipeService recipeService;

    // ──────────────────────────────── 导入 ────────────────────────────────

    @Transactional
    public MaterialImportReportDTO importLibrary(byte[] xlsxBytes) {
        long t0 = System.nanoTime();
        MaterialImportReportDTO report = new MaterialImportReportDTO();
        if (xlsxBytes == null || xlsxBytes.length == 0) {
            throw MaterialRecipeApiException.badRequest("IMPORT_FILE_EMPTY", "上传文件为空");
        }

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            // ① 旧模板拒收（AC-11）
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (LEGACY_SHEET_NAMES.contains(wb.getSheetName(i))) {
                    throw MaterialRecipeApiException.badRequest("IMPORT_TEMPLATE_OUTDATED",
                        "导入模板格式已更新，请下载新模板。新模板为单个工作表、4 列：材质 / 组号 / 元素符号 / 含量");
                }
            }
            if (wb.getNumberOfSheets() == 0) {
                throw MaterialRecipeApiException.badRequest("IMPORT_HEADER_INVALID", "表头不符合模板要求，请下载新模板");
            }

            // ② 读第一个工作表，按表头识别（不依赖 sheet 名，B-9）
            Sheet sheet = wb.getSheetAt(0);
            String sheetName = wb.getSheetName(0);
            assertHeader(sheet);

            // ③ 逐行解析 + 行级校验
            LinkedHashMap<String, MatEntry> materials = parseRows(sheet, sheetName, report);
            if (report.totalRows == 0) {
                throw MaterialRecipeApiException.badRequest("IMPORT_FILE_EMPTY", "文件没有可导入的数据行");
            }

            // ④ 校验（B-10 五级）+ ⑤⑥ 落库（B-11）
            persist(materials, sheetName, report);
        } catch (MaterialRecipeApiException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("材质库解析失败: " + e.getMessage(), e);
        }

        report.skippedRowCount = report.skipped.size();
        report.durationMs = (System.nanoTime() - t0) / 1_000_000;
        return report;
    }

    private void assertHeader(Sheet sheet) {
        Row h = sheet.getRow(sheet.getFirstRowNum() < 0 ? 0 : 0);
        if (h == null) {
            throw MaterialRecipeApiException.badRequest("IMPORT_HEADER_INVALID", "表头不符合模板要求，请下载新模板");
        }
        for (int i = 0; i < HEADER.size(); i++) {
            String actual = cellStr(h.getCell(i));
            if (!HEADER.get(i).equals(actual)) {
                throw MaterialRecipeApiException.badRequest("IMPORT_HEADER_INVALID", "表头不符合模板要求，请下载新模板");
            }
        }
    }

    /** 解析 + 行级校验（B-10 第①级）。返回按<b>材质在文件中首次出现顺序</b>排列的材质表。 */
    private LinkedHashMap<String, MatEntry> parseRows(Sheet sheet, String sheetName,
                                                      MaterialImportReportDTO report) {
        LinkedHashMap<String, MatEntry> materials = new LinkedHashMap<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String mat = cellStr(row.getCell(0));
            String groupLabel = cellStr(row.getCell(1));
            String symbol = cellStr(row.getCell(2));
            String contentRaw = cellStr(row.getCell(3));
            if (mat.isBlank() && groupLabel.isBlank() && symbol.isBlank() && contentRaw.isBlank()) {
                continue;                                   // 全空行不计
            }
            report.totalRows++;
            int excelRow = r + 1;                            // 1-based

            if (mat.isBlank()) {
                report.skipped.add(new SkippedRow(sheetName, excelRow, "材质为空", ""));
                continue;
            }
            if (symbol.isBlank()) {
                report.skipped.add(new SkippedRow(sheetName, excelRow, "元素符号为空", mat));
                continue;
            }
            // R1（task-0708）：不校验"非纯数字"——301/304/316/430/191/206/223/258/721 是合法钢/合金牌号（AC-27）。
            BigDecimal content = parseContent(contentRaw);
            if (!MaterialRecipeRules.pctInRange(content, BigDecimal.ONE)) {
                report.skipped.add(new SkippedRow(sheetName, excelRow, "含量非法", contentRaw));
                continue;
            }
            materials.computeIfAbsent(mat, k -> new MatEntry(mat, excelRow))
                     .put(groupLabel, symbol, content, excelRow);
        }
        return materials;
    }

    // ──────────────────────── 校验 + 落库 ────────────────────────

    @SuppressWarnings("unchecked")
    private void persist(LinkedHashMap<String, MatEntry> materials, String sheetName,
                         MaterialImportReportDTO report) {
        if (materials.isEmpty()) return;

        // ── 批量取数（常数条 SQL）──
        Set<String> matNames = materials.keySet();
        List<MaterialRecipe> existingRecipes = MaterialRecipe.<MaterialRecipe>find(
            "symbol in ?1 AND status = 'ACTIVE'", matNames).list();
        Map<String, List<MaterialRecipe>> recipesBySymbol = new LinkedHashMap<>();
        for (MaterialRecipe r : existingRecipes) {
            recipesBySymbol.computeIfAbsent(r.symbol, k -> new ArrayList<>()).add(r);
        }
        List<UUID> existingIds = existingRecipes.stream().map(r -> r.id).toList();
        Map<UUID, List<MaterialRecipeComposition>> compByRecipe =
            configService.listCompositionBatch(existingIds);
        // ⚠️ N+1 防线：这里一次把该批材质的<b>全部</b>配置（含 INACTIVE）拉进内存。
        //   含 INACTIVE 是必须的 —— 发号水位 max(seq) 要算上已停用的（M-2 编号不回收）；
        //   若只拉 ACTIVE，落库循环里就得对每条已存在材质各查一次水位，那就是 N+1。
        Map<UUID, List<MaterialRecipeConfig>> allConfigsByRecipe = loadAllConfigs(existingIds);
        Map<UUID, List<MaterialRecipeConfig>> activeConfigsByRecipe = new LinkedHashMap<>();
        Map<UUID, Integer> maxSeqByRecipe = new LinkedHashMap<>();
        Map<UUID, Integer> maxGroupNoByRecipe = new LinkedHashMap<>();
        List<UUID> activeConfigIds = new ArrayList<>();
        for (Map.Entry<UUID, List<MaterialRecipeConfig>> en : allConfigsByRecipe.entrySet()) {
            int max = 0;
            int maxGroup = 0;
            List<MaterialRecipeConfig> actives = new ArrayList<>();
            for (MaterialRecipeConfig c : en.getValue()) {
                if (c.seq > max) max = c.seq;
                int group = c.groupNo > 0 ? c.groupNo : c.seq;
                if (group > maxGroup) maxGroup = group;
                if (c.isActive()) { actives.add(c); activeConfigIds.add(c.id); }
            }
            maxSeqByRecipe.put(en.getKey(), max);
            maxGroupNoByRecipe.put(en.getKey(), maxGroup);
            activeConfigsByRecipe.put(en.getKey(), actives);
        }
        Map<UUID, List<MaterialRecipeElement>> elementsByConfig =
            configService.loadElementsByConfig(activeConfigIds);

        // ── B-10 校验：逐材质决定「整材质跳过 / 部分组跳过」──
        List<Map<String, Object>> compositionMismatches = new ArrayList<>();
        List<PassedMaterial> passed = new ArrayList<>();
        for (MatEntry m : materials.values()) {
            // 第③级：材质名长度（AC-24，DB 是 varchar(32)，超长必须应用层拦）
            if (m.name.length() > MaterialRecipeService.SYMBOL_MAX_LEN) {
                report.skipped.add(new SkippedRow(sheetName, m.firstRow,
                    "材质名超长（最多 " + MaterialRecipeService.SYMBOL_MAX_LEN + " 字符）", m.name));
                continue;
            }
            // 第③级续：M-6 同名材质防御分支（AC-28）
            List<MaterialRecipe> hits = recipesBySymbol.getOrDefault(m.name, List.of());
            if (hits.size() >= 2) {
                report.skipped.add(new SkippedRow(sheetName, m.firstRow,
                    "材质名对应多条材质记录，请先在材质管理页处理", m.name));
                continue;
            }
            MaterialRecipe existing = hits.isEmpty() ? null : hits.get(0);

            // 第②级：组级 Σ≈1（容差 0.02）
            List<Grp> validGroups = new ArrayList<>();
            for (Grp g : m.groupsSortedByLabel()) {
                if (g.contents.isEmpty()) continue;
                if (!g.duplicateSymbols.isEmpty()) {
                    report.skipped.add(new SkippedRow(sheetName, g.firstRow,
                        "同一组内元素符号重复：" + String.join(",", g.duplicateSymbols),
                        m.name + " 组" + g.label));
                    continue;
                }
                BigDecimal sum = g.sum();
                if (!MaterialRecipeRules.sumIsOneRatio(sum)) {
                    report.skipped.add(new SkippedRow(sheetName, g.firstRow,
                        "含量合计≠1(实际" + MaterialRecipeRules.formatRatioSum(sum) + ")",
                        m.name + " 组" + g.label));
                    continue;
                }
                validGroups.add(g);
            }
            if (validGroups.isEmpty()) continue;             // 无有效组 ⇒ 不建材质、不发号（AC-4）

            // 第④级：元素组成一致性 —— 按材质是否已存在分两条路
            if (existing != null) {
                List<MaterialRecipeComposition> comp = compByRecipe.getOrDefault(existing.id, List.of());
                List<String> compCodes = comp.stream().map(c -> c.elementCode).toList();
                List<Grp> kept = new ArrayList<>();
                for (Grp g : validGroups) {
                    if (!MaterialRecipeRules.elementSetsEqual(g.contents.keySet(), compCodes)) {
                        compositionMismatches.add(Map.of(
                            "material", m.name,
                            "row", g.firstRow,
                            "group", g.label,
                            "existingElements", MaterialRecipeRules.formatSet(new LinkedHashSet<>(compCodes), ","),
                            "incomingElements", MaterialRecipeRules.formatSet(g.contents.keySet(), ",")));
                        continue;
                    }
                    kept.add(g);
                }
                if (kept.isEmpty()) continue;
                passed.add(new PassedMaterial(m, existing, kept, comp));
            } else {
                // 新材质：先收齐该材质在本文件里的<b>全部</b>有效组再判（M-5b / D11 / AC-32）。
                // 🚫 不是「拿第一组当基准、后面逐组比」的行序依赖写法 —— 判据是集合相等，
                //    且不一致就整个材质跳过（不建材质、不发号、不入任何配置）。
                List<Set<String>> keySets = validGroups.stream()
                    .map(g -> (Set<String>) new LinkedHashSet<>(g.contents.keySet())).toList();
                int[] mismatch = MaterialRecipeRules.findFirstElementSetMismatch(keySets);
                if (mismatch != null) {
                    Grp gi = validGroups.get(mismatch[0]);
                    Grp gj = validGroups.get(mismatch[1]);
                    compositionMismatches.add(Map.of(
                        "material", m.name,
                        "row", m.firstRow,
                        "group", gi.label + "," + gj.label,
                        "existingElements", MaterialRecipeRules.formatSet(gi.contents.keySet(), ","),
                        "incomingElements", MaterialRecipeRules.formatSet(gj.contents.keySet(), ",")));
                    continue;
                }
                passed.add(new PassedMaterial(m, null, validGroups, null));
            }
        }
        // ── B-2：元素引用预校验（只读，绝不自动建档）──
        List<Map<String, Object>> elementIssues = new ArrayList<>();
        Map<String, ElementRef> elementIndex = loadAndValidateElementMaster(materials.values(), elementIssues);
        if (!compositionMismatches.isEmpty() || !elementIssues.isEmpty() || !report.skipped.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (!elementIssues.isEmpty()) {
                detail.put("missingElements", elementIssues.stream().flatMap(x -> ((List<String>) x.get("missingElements")).stream()).distinct().toList());
                detail.put("inactiveElements", elementIssues.stream().flatMap(x -> ((List<String>) x.get("inactiveElements")).stream()).distinct().toList());
            } else {
                detail.put("missingElements", List.of());
                detail.put("inactiveElements", List.of());
            }
            detail.put("compositionMismatches", compositionMismatches);
            detail.put("rowErrors", report.skipped);
            String message = !compositionMismatches.isEmpty()
                ? "导入失败：材质元素组成与既有组成不一致"
                : (!elementIssues.isEmpty() ? "导入失败：请先创建并启用所引用的元素" : "导入失败：文件存在校验错误");
            throw validationFailure(message, Map.of(
                "missingElements", detail.get("missingElements"),
                "inactiveElements", detail.get("inactiveElements"),
                "compositionMismatches", compositionMismatches,
                "rowErrors", report.skipped));
        }
        if (passed.isEmpty()) return;

        // ── B-11：落库（只增不改）──
        String nextCode = null;                              // 材质编号水位：只查一次，之后内存递增
        OffsetDateTime now = OffsetDateTime.now();
        for (PassedMaterial pm : passed) {
            MaterialRecipe recipe = pm.existing;
            boolean recipeIsNew = false;
            List<String> compositionCodes;

            if (recipe == null) {
                if (nextCode == null) nextCode = recipeService.nextRecipeCode();
                recipe = new MaterialRecipe();
                recipe.code = nextCode;
                nextCode = MaterialRecipeNumbering.nextRecipeCode(List.of(nextCode));
                recipe.symbol = pm.entry.name;
                recipe.name = pm.entry.name;                 // 名称默认 = 材质名
                recipe.specLabel = null;
                recipe.recipeType = "locked";
                recipe.status = "ACTIVE";
                recipe.allowCustomContent = false;           // M-5：导入新建的材质一律默认关
                recipe.sortOrder = parseSort(recipe.code);
                recipe.createdAt = now;
                recipe.updatedAt = now;
                recipe.persist();
                recipeIsNew = true;
                report.recipesCreated++;

                // 元素组成 = 各组一致的那个集合，顺序按元素在文件中首次出现的次序（M-5b）
                compositionCodes = new ArrayList<>(pm.groups.get(0).contents.keySet());
                int so = 1;
                for (String code : compositionCodes) {
                    ElementRef ref = elementIndex.get(code);
                    MaterialRecipeComposition c = new MaterialRecipeComposition();
                    c.recipeId = recipe.id;
                    c.elementNo = ref == null ? code : ref.elementNo;
                    c.elementCode = code;
                    c.elementName = ref == null ? DICT.getOrDefault(code, code) : ref.elementName;
                    c.sortOrder = so++;
                    c.createdAt = now;
                    c.persist();
                }
            } else {
                compositionCodes = pm.composition.stream().map(c -> c.elementCode).toList();
            }

            // 该材质已有的 ACTIVE 配置内容（M-3：只看 ACTIVE，不复活 INACTIVE）
            List<MaterialRecipeConfig> actives = recipeIsNew ? new ArrayList<>()
                : new ArrayList<>(activeConfigsByRecipe.getOrDefault(recipe.id, List.of()));
            List<Map<String, BigDecimal>> activeContents = new ArrayList<>();
            for (MaterialRecipeConfig c : actives) {
                activeContents.add(MaterialRecipeConfigService.contentByCode(
                    elementsByConfig.getOrDefault(c.id, List.of())));
            }
            // 水位取自上面一次性预载的内存快照，🚫 不在循环里查库（N+1）
            int seq = recipeIsNew ? 0 : maxSeqByRecipe.getOrDefault(recipe.id, 0);
            int groupNo = recipeIsNew ? 0 : maxGroupNoByRecipe.getOrDefault(recipe.id, 0);

            Map<String, Integer> orderByCode = new HashMap<>();
            for (int i = 0; i < compositionCodes.size(); i++) orderByCode.put(compositionCodes.get(i), i + 1);

            for (Grp g : pm.groups) {
                Map<String, BigDecimal> incoming = new LinkedHashMap<>();
                for (Map.Entry<String, BigDecimal> e : g.contents.entrySet()) {
                    incoming.put(e.getKey(), e.getValue().multiply(HUNDRED));   // ×100 归一（12 位无损）
                }
                boolean dup = false;
                for (Map<String, BigDecimal> known : activeContents) {
                    if (MaterialRecipeRules.sameContent(incoming, known)) { dup = true; break; }
                }
                if (dup) {
                    report.configsSkippedAsDuplicate++;
                    continue;
                }
                MaterialRecipeConfig cfg = configService.newConfig(recipe, ++seq, ++groupNo, null);
                List<ResolvedPct> rows = new ArrayList<>(incoming.size());
                for (Map.Entry<String, BigDecimal> e : incoming.entrySet()) {
                    ElementRef ref = elementIndex.get(e.getKey());
                    rows.add(new ResolvedPct(
                        ref == null ? e.getKey() : ref.elementNo,
                        e.getKey(),
                        ref == null ? DICT.getOrDefault(e.getKey(), e.getKey()) : ref.elementName,
                        e.getValue(),
                        orderByCode.getOrDefault(e.getKey(), rows.size() + 1)));
                }
                rows.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
                report.elementRowsInserted += configService.insertElements(cfg.id, rows);
                report.configsCreated++;
                activeContents.add(incoming);                 // 同材质后续组也要与本组判重
                report.createdConfigs.add(new CreatedConfig(
                    recipe.code, recipe.symbol, cfg.configNo, summarize(incoming), recipeIsNew));
            }
        }
        em.flush();
    }

    /** 一次 IN 取该批材质的<b>全部</b>配置（含 INACTIVE，发号水位要用），内存分组（无 N+1）。 */
    private Map<UUID, List<MaterialRecipeConfig>> loadAllConfigs(List<UUID> recipeIds) {
        if (recipeIds == null || recipeIds.isEmpty()) return Map.of();
        List<MaterialRecipeConfig> all = MaterialRecipeConfig.<MaterialRecipeConfig>find(
            "recipeId in ?1 ORDER BY seq", new LinkedHashSet<>(recipeIds)).list();
        Map<UUID, List<MaterialRecipeConfig>> byRecipe = new LinkedHashMap<>();
        for (MaterialRecipeConfig c : all) {
            byRecipe.computeIfAbsent(c.recipeId, k -> new ArrayList<>()).add(c);
        }
        return byRecipe;
    }

    /**
     * 一次批量读取材质文件引用的全部元素。缺失或停用时聚合问题，调用方在任何写入前整份拒绝。
     *
     * @return 符号 → ElementRef（仅现有元素；本方法只读）
     */
    @SuppressWarnings("unchecked")
    private Map<String, ElementRef> loadAndValidateElementMaster(Iterable<MatEntry> materialEntries,
                                                                  List<Map<String, Object>> issues) {
        // 收集本批用到的全部符号（保首现序，便于新建顺序稳定）
        LinkedHashMap<String, int[]> firstSeen = new LinkedHashMap<>();   // symbol → [excelRow]
        for (MatEntry entry : materialEntries) {
            for (Grp g : entry.groupsSortedByLabel()) {
                for (Map.Entry<String, Integer> e : g.firstRowBySymbol.entrySet()) {
                    if (!firstSeen.containsKey(e.getKey())) {
                        firstSeen.put(e.getKey(), new int[]{e.getValue()});
                    }
                }
            }
        }
        Map<String, ElementRef> index = new LinkedHashMap<>();
        if (firstSeen.isEmpty()) return index;

        // 1) 现有符号（一次查询）
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_no, element_code, element_name, status FROM element WHERE element_code IN (:codes)")
            .setParameter("codes", firstSeen.keySet())
            .getResultList();
        for (Object[] r : rows) {
            index.put((String) r[1], new ElementRef((String) r[0], (String) r[1], (String) r[2]));
        }

        // 2) 缺失/停用元素聚合后整份拒绝；材质导入不得写 element。
        List<String> missing = firstSeen.keySet().stream().filter(c -> !index.containsKey(c)).toList();
        List<String> inactive = new ArrayList<>();
        for (Object[] r : rows) {
            if (!"ACTIVE".equalsIgnoreCase(r[3] == null ? "" : String.valueOf(r[3]))) inactive.add(String.valueOf(r[1]));
        }
        if (!missing.isEmpty() || !inactive.isEmpty()) {
            issues.add(Map.of("missingElements", missing, "inactiveElements", inactive));
        }
        return index;
    }

    private MaterialRecipeApiException validationFailure(String message, Map<String, Object> detail) {
        return new MaterialRecipeApiException(400, "MATERIAL_IMPORT_VALIDATION_FAILED", message, detail);
    }

    /** 'Ag 90% / Ni 10%'（100 制，去尾随零仅用于这条摘要文案）。 */
    private String summarize(Map<String, BigDecimal> contentPct) {
        List<String> parts = new ArrayList<>(contentPct.size());
        for (Map.Entry<String, BigDecimal> e : contentPct.entrySet()) {
            parts.add(e.getKey() + " " + e.getValue().stripTrailingZeros().toPlainString() + "%");
        }
        return String.join(" / ", parts);
    }

    // ──────────────────────────────── 干净模板 ────────────────────────────────

    /** 生成<b>单 sheet 4 列</b>空模板（材质 / 组号 / 元素符号 / 含量）+ 1 行示例 + 含量批注。 */
    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            Row h = s.createRow(0);
            for (int i = 0; i < HEADER.size(); i++) h.createCell(i).setCellValue(HEADER.get(i));
            exampleRow(s, 1, "纯银", "1", "Ag", 1.0);

            CreationHelper factory = wb.getCreationHelper();
            Drawing<?> drawing = s.createDrawingPatriarch();
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(3); anchor.setCol2(8); anchor.setRow1(0); anchor.setRow2(6);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString(
                "含量填 0–1 小数，最多 12 位；同一材质同一组号内相加=1。\n"
                    + "组号只用于在本表内给同一材质分组（一个材质可有多组不同含量配比）；入库时由系统按历史水位生成新的配置组号。\n"
                    + "材质编号、配置编号与元素编号<b>不用填</b>：材质和配置由系统自动发号，元素须先在元素主数据中创建并启用。"));
            s.getRow(0).getCell(3).setCellComment(comment);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成导入模板失败: " + e.getMessage(), e);
        }
    }

    private void exampleRow(Sheet s, int rowIdx, String mat, String group, String symbol, double content) {
        Row r = s.createRow(rowIdx);
        r.createCell(0).setCellValue(mat);
        r.createCell(1).setCellValue(group);
        r.createCell(2).setCellValue(symbol);
        r.createCell(3).setCellValue(content);
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private int parseSort(String code) {
        try { return Integer.parseInt(code.trim()); } catch (Exception e) { return 0; }
    }

    private BigDecimal parseContent(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }

    /** 单元格取字符串：STRING 原样、NUMERIC 无科学计数/无多余小数、FORMULA 取缓存值。 */
    private String cellStr(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                return numToStr(c.getNumericCellValue());
            case BOOLEAN:
                return Boolean.toString(c.getBooleanCellValue());
            case FORMULA:
                try {
                    switch (c.getCachedFormulaResultType()) {
                        case STRING: return c.getStringCellValue().trim();
                        case NUMERIC: return numToStr(c.getNumericCellValue());
                        default: return "";
                    }
                } catch (Exception e) {
                    return "";
                }
            default:
                return "";
        }
    }

    private String numToStr(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    // ──────────────────────────────── 中间结构 ────────────────────────────────

    /** 文件内的一个材质：按<b>首现顺序</b>保留其各组。 */
    private static final class MatEntry {
        final String name;
        final int firstRow;
        final LinkedHashMap<String, Grp> groups = new LinkedHashMap<>();

        MatEntry(String name, int firstRow) {
            this.name = name;
            this.firstRow = firstRow;
        }

        void put(String groupLabel, String symbol, BigDecimal content, int excelRow) {
            groups.computeIfAbsent(groupLabel, k -> new Grp(groupLabel, excelRow))
                  .put(symbol, content, excelRow);
        }

        /**
         * 🚨 <b>顺序无关性的落脚点之一</b>（AC-32）：组一律按<b>组号</b>排序后再参与校验与发号，
         * 不按它们在文件里出现的先后。否则「把两组的行块对调」会让报告里的
         * 「组X / 组Y」互换、配置发号顺序颠倒 —— 同样的数据换个行序结果就不一样了。
         * 组号能解析成整数就按数值比，否则退化为字符串比。
         */
        List<Grp> groupsSortedByLabel() {
            List<Grp> out = new ArrayList<>(groups.values());
            out.sort((a, b) -> {
                Long na = asLong(a.label), nb = asLong(b.label);
                if (na != null && nb != null) return Long.compare(na, nb);
                if (na != null) return -1;
                if (nb != null) return 1;
                return a.label.compareTo(b.label);
            });
            return out;
        }

        private static Long asLong(String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
        }
    }

    /** 一组含量配比（组号只在文件内分组，不落库）。组内同符号重复时末值胜、保首现位置。 */
    private static final class Grp {
        final String label;
        final int firstRow;
        final LinkedHashMap<String, BigDecimal> contents = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> firstRowBySymbol = new LinkedHashMap<>();
        final LinkedHashSet<String> duplicateSymbols = new LinkedHashSet<>();

        Grp(String label, int firstRow) {
            this.label = label;
            this.firstRow = firstRow;
        }

        void put(String symbol, BigDecimal content, int excelRow) {
            if (contents.containsKey(symbol)) duplicateSymbols.add(symbol);
            else contents.put(symbol, content);
            firstRowBySymbol.putIfAbsent(symbol, excelRow);
        }

        BigDecimal sum() {
            BigDecimal s = BigDecimal.ZERO;
            for (BigDecimal v : contents.values()) s = s.add(v);
            return s;
        }
    }

    /** 通过全部校验、可以落库的材质。 */
    private static final class PassedMaterial {
        final MatEntry entry;
        final MaterialRecipe existing;                       // null = 新材质
        final List<Grp> groups;                              // 通过校验的组
        final List<MaterialRecipeComposition> composition;   // 仅已存在材质有

        PassedMaterial(MatEntry entry, MaterialRecipe existing, List<Grp> groups,
                       List<MaterialRecipeComposition> composition) {
            this.entry = entry;
            this.existing = existing;
            this.groups = groups;
            this.composition = composition;
        }
    }
}
