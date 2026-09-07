#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
task-260819 · v9 · B-42 / B-43 / B-44①  语义图种子 + 全版本视图  生成器（D-82）

唯一输入： dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md
           （由 task-260902 的 scratchpad/gen_matrix.py 从三份 Excel 机器生成）

产出（四份迁移，均为纯生成物，🚫 不要手改）：
  1) V412__task260819_v9_cost_all_version_views.sql
       核价两套 26 张带版本表的 v_<主表>_all 全版本视图（S-31 / D-84 / AC-109 / AC-125）
  2) V413__task260819_v9_semantic_graph_reseed.sql
       删 V6 语义图种子 + 灌 41 张进图主表的节点/列/边/三套页签视图（S-23 / AC-102~AC-106）
       + 料号桥 LOOKUP 节点与边（S-24 / AC-111 / AC-112）
  3) V414__task260819_v9_bridge_cardinality_fix.sql
       28 条料号桥边的 cardinality 修正 MANY_TO_ONE → ONE_TO_MANY（AC-121）
  4) V416__task260819_v9_bridge_narrow_form.sql
       料号桥从「输出 LOOKUP」改成「输入收窄 NARROW」+ 摘掉 32 行 AUX 挂载
       （用户 2026-09-04 裁决；V414 自此无实际意义但不撤销）
  5) V417__task260819_v9_tab_type_key_value_fix.sql
       semantic_tab_view.tab_type 的 3 行「BOM 树」→「BOM」（B-54 / D-39）
       —— V413 把**显示名**写进了**键值列**，导致配置器配「BOM 树」页签的新组件
       PUT builder 直接 400 存不进去（用户 2026-09-05 批准）

🔒 V412 / V413 / V414 / V416 **已应用到共享库**，checksum 被 Flyway 锁死。
   本脚本必须继续把它们逐字节原样产出 —— 任何内容变化都会让所有人的服务 validate 失败
   （CLAUDE.md §3.2 契约销毁）。⇒ 对语义图的后续修正一律走**新的增量迁移**，
   像 V414 那样；🚫 不许"重新生成一遍 V413 让它变成新的正确状态"。
   `--check` 会同时守住这三份的 md5，V412/V413 变红就是踩了这条线。

可重放性（AC-103）：本脚本无随机性、无时间戳、无库访问。
  · 全部主键用 UUIDv5（固定 namespace + 稳定 key 串）⇒ 重跑逐字节相同。
  · 校验方式： python3 gen_v9_semantic_seed.py --check   （不写文件，只比对 md5）

────────────────────────────────────────────────────────────────────────────
建模规则（字段矩阵里没有"角色"元数据，下列规则是本脚本的确定性推导，改规则=改产物）
────────────────────────────────────────────────────────────────────────────
R1 轴列   axis = 名为 material_no（QUOTE）/ production_no（COST_*）的列，没有则 None。
R2 维度段 dimColumns = 「对比项」标记的列，按声明顺序取，**遇到第一个数值型对比项列即停**。
          （三份 Excel 的排布一致：维度列在前、值列在后。）
R3 is_code = 是轴列，或在 dimColumns 里，或列名以 _no / _code 结尾。
R4 PART_NO = dimColumns 里第一个「料号形态」列（名字以 material_no / part_no / component_no 结尾）；
          没有则用轴列；再没有则用第一个 is_code 列。
          ⚠️ 不能简单取「第一个 _no 结尾的维度列」—— operation_no（工序编号）会被误当料号。
R5 ROW_KEY = 轴列 + 全部 dimColumns。
R6 SORT    = item_seq（存在则）。
R7 grain_columns = dimColumns 去掉轴列（编译器用它拼 ORDER BY，见 SemanticCompiler#compile）。
R8 data_type = numeric/integer → NUMBER，其余 → TEXT。
          🚫 刻意不产出 MONEY：MONEY 会触发保存期「金额↔小计成对」体检（B-12/AC-13），
             矩阵里没有任何字段能可靠区分"金额"与"普通数值"，误判会变成**保存被拒**。
R9 short_name = 中文 sheet 名（QUOTE 方言的列别名是 _<short_name>_<显示名>，AC-110）。
          脚本内断言：别名 UTF-8 字节长度 < 63（PG 标识符上限，超限是**静默截断**）。
R10 anchor_expr = baseAlias(physical_table) + "." + (axis or 第一个 is_code 列)
          baseAlias 必须与 SemanticCompiler#baseAlias 逐字同规则（下划线分段取首字母），
          否则编译期 COMPILE_ALIAS_DRIFT 直接 500。

────────────────────────────────────────────────────────────────────────────
页签映射（需求文档 §9.2）
────────────────────────────────────────────────────────────────────────────
  *_material        → 主件
  *_element_bom     → 材质元素
  *_material_bom    → 零件 / 外购件 / BOM 树（同一锚点三个页签）
  *_plating_scheme  → 孤儿节点：登记进图但不挂任何页签（它不是费用，也没有轴列）
  其余              → 费用类的一个 variant（variant_key = node_key）
  不进图            → 年降 3 张（N-18） + ds_quote_customer_part（N-19） + 全部 _history（N-17）
"""

import hashlib
import re
import sys
import uuid
from pathlib import Path

# ── 路径 ────────────────────────────────────────────────────────────────────
ROOT = Path(__file__).resolve().parents[3]
MATRIX = ROOT / "dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md"
MIGDIR = ROOT / "cpq-backend/src/main/resources/db/migration"
OUT_VIEWS = MIGDIR / "V412__task260819_v9_cost_all_version_views.sql"
OUT_SEED = MIGDIR / "V413__task260819_v9_semantic_graph_reseed.sql"
OUT_CARD_FIX = MIGDIR / "V414__task260819_v9_bridge_cardinality_fix.sql"
OUT_NARROW = MIGDIR / "V416__task260819_v9_bridge_narrow_form.sql"
OUT_TAB_TYPE = MIGDIR / "V417__task260819_v9_tab_type_key_value_fix.sql"

# ── 料号桥边的基数声明 ───────────────────────────────────────────────────────
# 🚨 两个常量，不要合并成一个：
#   V413 已应用到共享库 cpq_db_0724 且已合 master ⇒ 它的 checksum 被 Flyway 锁死，
#   内容改一个字节，所有人的服务都会 validate 失败启动不了（CLAUDE.md §3.2 契约销毁）。
#   所以 V413 必须继续原样产出历史值，修正走增量迁移 V414。
BRIDGE_CARDINALITY_V413 = "MANY_TO_ONE"    # 🔒 冻结的历史值，🚫 不许改（改了 V413 的 md5 就变）
BRIDGE_CARDINALITY = "ONE_TO_MANY"         # 由 V414 修正到位；改 NARROW 后已无实际意义（无 JOIN 即无基数），保留不撤销

# ── 料号桥的形态（2026-09-04 用户裁决：桥从「输出列」改成「输入收窄」）────────────
# 旧形态（V413）：LOOKUP 边 + 桥挂 AUX ⇒ 编译成
#     LEFT JOIN ds_quote_material ON dqm.production_no = 锚点.production_no
#   方向是 生产料号 → 销售料号，而这个方向 24 个生产料号对 45 个销售料号 ⇒ **会扇出**。
# 新形态（V416）：NARROW 边 + 桥不挂 AUX ⇒ 编译成
#     WHERE 锚点.production_no IN (SELECT production_no FROM ds_quote_material
#                                  WHERE material_no = ANY(:total_material_no))
#   方向是 销售料号 → 生产料号，45 行 45 个不同 material_no、重复组 0 ⇒ **永不扇出**。
#   业务上销售料号是**入参**（产品卡片的基础属性），不是核价页签的展示列。
#
# 🔒 同样是「冻结历史值 + 现行真值」两个常量：V413 已应用共享库，checksum 锁死。
BRIDGE_EDGE_KIND_V413 = "LOOKUP"           # 🔒 冻结的历史值，🚫 不许改
BRIDGE_EDGE_KIND = "NARROW"                # ✅ 现行契约（主线 2026-09-04 定，编译器侧同名分支），🚫 别改名
# V413 里桥是否挂成页签的 AUX 数据源（＝是否出现在字段面板里可拖）。
BRIDGE_AS_AUX_V413 = True                  # 🔒 冻结的历史行为
BRIDGE_AS_AUX = False                      # ✅ 现行：桥只做收窄，不是可拖数据源（V416 摘掉挂载）

# ── 页签类型：键值 vs 显示名（需求文档 D-39，2026-08-21 裁决）────────────────
# 🚨 这里是**同一个缺陷的第二次**。D-39 原文：
#     · 存储值 = 'BOM'   —— component.tab_type / ComponentService.VALID_TAB_TYPES /
#                           semantic_tab_view.tab_type / builder_config.tabType，四处统一
#     · 显示名 = 「BOM 树」—— Select 的 label、图谱页文案、文档正文
#   第一次犯：前端把 TAB_TYPES 写成 'BOM 树'，includes() 静默 miss，存量 tab_type='BOM'
#            的组件打开取数配置 Tab 被误初始化成「主件」（D-39 的「起因」栏）。
#   第二次犯（本处）：本脚本把**显示名**写进了 semantic_tab_view.tab_type 这个**键值列**，
#            三个方言全错。后果：用配置器配「BOM 树」页签的新组件
#            PUT /api/cpq/components/{id}/builder → 400 Invalid tabType: BOM 树，**根本存不进去**。
#   ⇒ 存量口径实测站在 'BOM' 一边：component.tab_type 里 BOM=21 行、'BOM 树' 0 行。
#
# 🔒 与料号桥同一套「冻结历史值 + 现行真值」手法：V413 已应用共享库、checksum 被 Flyway 锁死，
#    必须继续原样产出**错的**历史值；修正走增量迁移 V417（下面 gen_tab_type_fix）。
# ⚠️ TAB_TYPE_TREE_V413 同时是页签视图主键 UUIDv5 的**输入键串**
#    （uid("tv:<dialect>:<tab>:<variant>")）—— 换成 'BOM' 会让 3 个主键变成另外 3 个 UUID，
#    而 V417 是原地 UPDATE、id 不变。所以 uid() 的键串**永远用冻结值**，
#    只有写进 tab_type 列的那个**取值**才随 V417 切到现行真值。
TAB_TYPE_TREE_V413 = "BOM 树"              # 🔒 冻结的历史值 —— ❌ 它是错的（显示名进了键值列），🚫 不许改
TAB_TYPE_TREE = "BOM"                      # ✅ 现行真值 = ComponentService.VALID_TAB_TYPES 里的存储值

# 冻结历史值 → 现行真值。V413 恒发左边，V417 把库里的左边改成右边；
# 将来任何**新**种子一律走 tab_type_current()，🚫 不要再直接写字面量。
TAB_TYPE_V413_TO_CURRENT = {TAB_TYPE_TREE_V413: TAB_TYPE_TREE}


def tab_type_current(tab_key):
    """页签类型的**现行存储值**（写进 semantic_tab_view.tab_type 的值域，
    权威值域 = ComponentService.VALID_TAB_TYPES，启动期由 SemanticGraphKeyValueSelfCheck 兜底）。

    入参 tab_key 是 V413 冻结的键串（也是 UUIDv5 的输入，不可变）。"""
    return TAB_TYPE_V413_TO_CURRENT.get(tab_key, tab_key)

# ── 固定 namespace（🚫 永远不要改：改了全部 UUID 主键都会变） ────────────────
NS = uuid.UUID("6f1a2c34-8e7b-5d90-a1b2-c3d4e5f60718")

# ── 常量 ────────────────────────────────────────────────────────────────────
DIALECTS = {
    "报价数据": ("QUOTE", "ds_quote_", "material_no"),
    "基础核价": ("COST_BASIC", "ds_cost_basic_", "production_no"),
    "详细核价": ("COST_DETAIL", "ds_cost_detail_", "production_no"),
}

# 不进图（N-18 年降 3 张 / N-19 客户料号）
EXCLUDED_TABLES = {
    "ds_quote_assembly_fee_annual",
    "ds_quote_incoming_annual",
    "ds_quote_annual_discount",
    "ds_quote_customer_part",
}

# 建表器统一追加的系统列（顺序 = 迁移 V405~V407 的建表顺序）
SYS_HEAD = ["id"]
SYS_VER = ["version_no", "row_fingerprint"]
SYS_TAIL = ["source", "created_at", "created_by", "updated_at", "updated_by"]

# AC-102 点名必须归零的 V6 物理表（写进迁移注释，便于人工复核）
V6_TABLES = [
    "material_bom_item", "element_bom_item", "unit_price", "annual_discount",
    "capacity", "plating_scheme", "material_customer_map", "material_master",
]

PART_NO_SUFFIXES = ("material_no", "part_no", "component_no")


def uid(key: str) -> str:
    return str(uuid.uuid5(NS, key))


def base_alias(table: str) -> str:
    """必须与 SemanticCompiler#baseAlias 逐字同规则。"""
    if not table:
        return "t"
    if "_" in table:
        sb = "".join(seg[0] for seg in table.split("_") if seg)
        return sb if sb else table[:2]
    return table[:2]


def q(s):
    """SQL 单引号字面量；None → NULL。"""
    if s is None:
        return "NULL"
    return "'" + str(s).replace("'", "''") + "'"


def arr(items):
    if not items:
        return "'{}'"
    return "ARRAY[" + ",".join(q(i) for i in items) + "]::TEXT[]"


# ── 解析字段矩阵 ────────────────────────────────────────────────────────────
RE_SECTION = re.compile(r"^## (报价数据|基础核价|详细核价)")
RE_TABLE = re.compile(r"^### (.+?) → `([a-z0-9_]+)` (🔓 免版本|🕐 \*\*带版本\*\*)")
RE_ROW = re.compile(r"^\|\s*(\d+|—)\s*\|(.+)$")


def parse_matrix(text: str):
    dialect = None
    cur = None
    tables = []
    for line in text.splitlines():
        m = RE_SECTION.match(line)
        if m:
            dialect = DIALECTS[m.group(1)][0]
            continue
        m = RE_TABLE.match(line)
        if m:
            cur = {
                "cn": m.group(1).strip(),
                "table": m.group(2),
                "versioned": "带版本" in m.group(3),
                "dialect": dialect,
                "cols": [],
            }
            tables.append(cur)
            continue
        if cur is None:
            continue
        m = RE_ROW.match(line)
        if not m:
            continue
        cells = [c.strip() for c in m.group(2).split("|")]
        # cells = [Excel列名, 底色, 建字段, 字段名, PG类型, 标记, (尾部空)]
        if len(cells) < 6:
            continue
        label, _fill, built, name, pgtype, mark = cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]
        if not built.startswith("✅"):
            continue  # 白底 NAME 列：不建字段（AC-3），也不进语义图
        name = name.strip("`")
        cur["cols"].append({
            "label": label,
            "db": name,
            "pg": pgtype,
            "mark": mark,
            "compared": mark == "对比项",
            "axis_mark": mark == "轴",
            "numeric": pgtype.startswith("numeric") or pgtype == "integer",
        })
    return tables


# ── 建模推导 ────────────────────────────────────────────────────────────────
def derive(t):
    dialect = t["dialect"]
    axis_name = {"QUOTE": "material_no", "COST_BASIC": "production_no", "COST_DETAIL": "production_no"}[dialect]
    names = [c["db"] for c in t["cols"]]
    axis = axis_name if axis_name in names else None

    # R2 维度段
    dims = []
    for c in t["cols"]:
        if not c["compared"]:
            continue
        if c["numeric"]:
            break
        dims.append(c["db"])

    # R3 is_code
    def is_code(c):
        return c["db"] == axis or c["db"] in dims or c["db"].endswith("_no") or c["db"].endswith("_code")

    # R4 PART_NO
    part_no = None
    for d in dims:
        if d.endswith(PART_NO_SUFFIXES):
            part_no = d
            break
    if part_no is None:
        part_no = axis
    if part_no is None:
        for c in t["cols"]:
            if is_code(c):
                part_no = c["db"]
                break

    row_keys = ([axis] if axis else []) + [d for d in dims if d != axis]

    cols = []
    for i, c in enumerate(t["cols"]):
        roles = []
        if c["db"] == part_no:
            roles.append("PART_NO")
        if c["db"] in row_keys:
            roles.append("ROW_KEY")
        if c["db"] == "item_seq":
            roles.append("SORT")
        cols.append({
            "db": c["db"],
            "label": c["label"],
            "type": "NUMBER" if c["numeric"] else "TEXT",
            "is_code": is_code(c),
            "roles": roles,
            "sort": i,
        })

    suffix = t["table"]
    for _cn, (d, prefix, _ax) in DIALECTS.items():
        if d == dialect and suffix.startswith(prefix):
            suffix = suffix[len(prefix):]
            break

    # 核价两套的带版本表指向全版本视图（B-44② / S-31②）
    physical = t["table"]
    view = None
    if dialect in ("COST_BASIC", "COST_DETAIL") and t["versioned"]:
        view = "v_%s_all" % t["table"]
        physical = view

    anchor_col = axis or (part_no if part_no else cols[0]["db"])
    t.update({
        "suffix": suffix,
        "node_key": suffix.upper(),
        "axis": axis,
        "dims": dims,
        "part_no": part_no,
        "cols_out": cols,
        "physical": physical,
        "view": view,
        "grain": [d for d in dims if d != axis],
        "anchor_expr": "%s.%s" % (base_alias(physical), anchor_col),
        "scope": "FULL" if axis else "NONE",
    })
    return t


def tab_of(t):
    """→ [(tab_type, variant_key, variant_label)]；空 = 孤儿节点。"""
    s = t["suffix"]
    if s == "material":
        return [("主件", "", None)]
    if s == "element_bom":
        return [("材质元素", "", None)]
    if s == "material_bom":
        # ⚠️ 第三项恒发**冻结值** TAB_TYPE_TREE_V413（= "BOM 树"）：它既是 V413 的列取值
        #    （checksum 锁死），也是页签视图主键 UUIDv5 的键串（V417 原地 UPDATE 不换 id）。
        #    现行真值经 tab_type_current() 取，由 V417 落库。
        return [("零件", "", None), ("外购件", "", None), (TAB_TYPE_TREE_V413, "", None)]
    if s == "plating_scheme":
        return []
    return [("费用类", t["node_key"], t["cn"])]


# ── 生成：26 张全版本视图 ───────────────────────────────────────────────────
VIEW_COMMENT = (
    "task-260819 v9 · S-31/D-84 全版本视图：{main} UNION ALL {main}_history。"
    "唯一用途 = 核价单按料号切版本（{axis} 粒度，costing_order_version_override）。"
)
IS_CURRENT_COMMENT = (
    "🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 {main}_history → false。"
    "与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、"
    "可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；"
    "本列没有任何写入路径，UPDATE/索引/触发器一概不适用。"
    "新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。"
)


def gen_views(tables):
    out = []
    out.append("-- V412__task260819_v9_cost_all_version_views.sql")
    out.append("-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。")
    out.append("-- task-260819 · v9 · B-44① / S-31 / D-84（AC-109 / AC-125 / AC-126）")
    out.append("--")
    out.append("-- 为核价两套的带版本主表各建一张 v_<主表>_all 全版本视图：")
    out.append("--     SELECT <主表列>, true  AS is_current FROM <主表>")
    out.append("--     UNION ALL")
    out.append("--     SELECT <同列>,   false AS is_current FROM <主表>_history")
    out.append("-- 编译器据此发 :versionFilter(<别名>.is_current, <别名>.version_no::text, <别名>.<轴列>)。")
    out.append("--")
    out.append("-- 🚫 报价侧那 13 张带版本表不建视图 —— 版本切换是核价侧独有功能（S-31）。")
    out.append("-- ⚠️ 视图建在 task-260902 的表上：不改表结构、不加索引、不加约束。")
    out.append("-- 🚨 UNION 显式列举列 ⇒ 对方给主表加列时视图**不报错、只静默丢列**。")
    out.append("--    这条静默失败由启动期自检 com.cpq.builder.selfcheck.CostAllVersionViewSelfCheck 拦住（AC-125）。")
    out.append("")
    n = 0
    for t in tables:
        if not t["view"]:
            continue
        n += 1
        main = t["table"]
        cols = SYS_HEAD + [c["db"] for c in t["cols_out"]] + SYS_VER + SYS_TAIL
        collist = ", ".join(cols)
        out.append("-- %d) %s（%s）" % (n, main, t["cn"]))
        out.append("CREATE VIEW %s AS" % t["view"])
        out.append("SELECT %s, true  AS is_current FROM %s" % (collist, main))
        out.append("UNION ALL")
        out.append("SELECT %s, false AS is_current FROM %s_history;" % (collist, main))
        out.append("COMMENT ON VIEW %s IS %s;" % (
            t["view"], q(VIEW_COMMENT.format(main=main, axis=t["axis"]))))
        out.append("COMMENT ON COLUMN %s.is_current IS %s;" % (
            t["view"], q(IS_CURRENT_COMMENT.format(main=main))))
        out.append("")
    out.insert(2, "-- 视图张数：%d（ds_cost_basic_* 9 + ds_cost_detail_* 17）" % n)
    assert n == 26, "全版本视图应为 26 张，实得 %d" % n
    return "\n".join(out) + "\n"


# ── 生成：语义图种子 ────────────────────────────────────────────────────────
def gen_seed(tables):
    graph = [t for t in tables if t["table"] not in EXCLUDED_TABLES]
    o = []
    o.append("-- V413__task260819_v9_semantic_graph_reseed.sql")
    o.append("-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。")
    o.append("-- task-260819 · v9 · B-42 / B-43（S-23 / S-24，AC-102~AC-106 / AC-111 / AC-112）")
    o.append("--")
    o.append("-- 🚨 破坏性：先整块删除 V6 时代的语义图种子（created_by='seed'），再灌新图。")
    o.append("--    删除面（2026-09-03 在 cpq_db_0724 实测）：")
    o.append("--      semantic_node 23 / semantic_node_column 145 / semantic_edge 25 / semantic_edge_key 29")
    o.append("--      semantic_tab_view 7 / semantic_tab_view_node 17 / semantic_tab_view_column 23")
    o.append("--    可恢复性：这些行 100% 由迁移 V388/V389/V393/V395 产生，无用户数据；")
    o.append("--    要还原直接重放那四个迁移的 INSERT 段即可。")
    o.append("--    ⚠️ 删除只针对 created_by='seed' —— 若有人经 POST /semantic-graph 建过节点，")
    o.append("--       semantic_edge/semantic_tab_view_node 的 RESTRICT 外键会让本迁移**报错中止**")
    o.append("--       （这正是要的行为：不静默毁掉别人的配置）。")
    o.append("--")
    o.append("-- 🚫 不进图：全部 *_history（N-17）/ 年降 3 张（N-18）/ ds_quote_customer_part（N-19）")
    o.append("-- 🚫 不进图：material_master / material_customer_map 等 V6 查名维表（AC-102 点名归零）")
    o.append("--    ⇒ 新图暂无「名称列查名」LOOKUP（字段矩阵里的白底名称列由 dataset 侧 JOIN 主数据带出，")
    o.append("--      不是语义图的职责，也没有任何一条 v9 AC 要求它）。")
    o.append("")
    o.append("-- ============ 1. 删除 V6 种子（顺序按外键依赖：叶→根） ============")
    o.append("DELETE FROM semantic_tab_view_column WHERE created_by = 'seed';")
    o.append("DELETE FROM semantic_tab_view_node   WHERE created_by = 'seed';")
    o.append("DELETE FROM semantic_tab_view        WHERE created_by = 'seed';")
    o.append("DELETE FROM semantic_edge_key WHERE edge_id IN (SELECT id FROM semantic_edge WHERE created_by = 'seed');")
    o.append("DELETE FROM semantic_edge            WHERE created_by = 'seed';")
    o.append("DELETE FROM semantic_node_column WHERE node_id IN (SELECT id FROM semantic_node WHERE created_by = 'seed');")
    o.append("DELETE FROM semantic_node            WHERE created_by = 'seed';")
    o.append("")
    o.append("-- AC-102 断言（人工复核用，不阻断）：以下 V6 表名此后在 semantic_node 中必须为 0 行")
    o.append("--   " + " / ".join(V6_TABLES))
    o.append("")

    # ---- 节点 ----
    o.append("-- ============ 2. 节点（41 张进图主表 + 2 个料号桥 LOOKUP + 1 个价格策略 FUNCTION） ============")
    node_rows = []
    for t in graph:
        nid = uid("node:%s:%s" % (t["dialect"], t["node_key"]))
        t["id"] = nid
        note = None
        if not tab_of(t):
            note = "孤儿节点：不挂任何页签（无轴列、也不是费用），仅登记以保持 45 张主表的完整可见性"
        node_rows.append((nid, t["node_key"], t["cn"], t["cn"], "SHEET", t["physical"], t["scope"],
                          t["anchor_expr"], t["grain"], None, None, None, None, t["dialect"], note))

    # 料号桥（B-43 / S-24 / D-76）
    bridge_ids = {}
    bridge_src = None
    for t in graph:
        if t["dialect"] == "QUOTE" and t["suffix"] == "material":
            bridge_src = t
    assert bridge_src is not None
    for d in ("COST_BASIC", "COST_DETAIL"):
        bid = uid("node:%s:QUOTE_MATERIAL_BRIDGE" % d)
        bridge_ids[d] = bid
        node_rows.append((bid, "QUOTE_MATERIAL_BRIDGE", "报价物料（料号桥）", "料号桥", "LOOKUP",
                          "ds_quote_material", "NONE", None, [], None, None, None, None, d,
                          "S-24/D-76：核价侧轴是生产料号，报价单行给的是销售料号 —— 本节点把两者接起来。"
                          "LOOKUP ⇒ 编译成 LEFT JOIN：桥里没有对应行时返回 0 行/NULL，**不抛异常**（AC-112）。"
                          "⚠️ 两表无外键，是文本列匹配；production_no 为空是正常业务状态"
                          "（报价时生产料号可能还没定，后期维护且可改），表现为「未关联核价数据」"))

    # 价格策略函数节点（沿用 V6 声明，仅 QUOTE 方言）
    func_id = uid("node:QUOTE:FUNC_ELEMENT_PRICE")
    node_rows.append((func_id, "FUNC_ELEMENT_PRICE", "价格策略 f_material_element_price", "价格策略",
                      "FUNCTION", None, "NONE", None, [], None,
                      "f_material_element_price(:customerCode, :priceBaseDate)", None, None, "QUOTE",
                      "别名固定为 cep（AC-1 铁律）；不是 f_customer_element_price。"
                      "只挂 QUOTE 方言：函数按**销售料号**取价，核价侧锚点是生产料号，语义对不上，不硬接"))

    o.append("INSERT INTO semantic_node (id,node_key,display_name,short_name,node_kind,physical_table,"
             "scope,anchor_expr,grain_columns,fixed_predicate,func_signature,discriminator,source_handler,"
             "dialect,note,created_by) VALUES")
    vals = []
    for r in node_rows:
        vals.append("(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'seed')" % (
            q(r[0]), q(r[1]), q(r[2]), q(r[3]), q(r[4]), q(r[5]), q(r[6]), q(r[7]), arr(r[8]),
            q(r[9]), q(r[10]), q(r[11]), q(r[12]), q(r[13]), q(r[14])))
    o.append(",\n".join(vals) + ";")
    o.append("")

    # ---- 列 ----
    o.append("-- ============ 3. 节点列声明（与 information_schema 双向无差集，AC-104） ============")
    col_ids = {}
    vals = []
    ncols = 0
    for t in graph:
        for c in t["cols_out"]:
            cid = uid("col:%s:%s:%s" % (t["dialect"], t["node_key"], c["db"]))
            col_ids[(t["dialect"], t["node_key"], c["db"])] = cid
            vals.append("(%s,%s,%s,%s,%s,%s,%s,%d)" % (
                q(cid), q(t["id"]), q(c["db"]), q(c["label"]), q(c["type"]),
                "TRUE" if c["is_code"] else "FALSE", arr(c["roles"]), c["sort"]))
            ncols += 1
    # 桥节点的列 = ds_quote_material 的业务列（供核价侧取销售料号/品名/规格等）
    for d, bid in bridge_ids.items():
        for c in bridge_src["cols_out"]:
            cid = uid("col:%s:QUOTE_MATERIAL_BRIDGE:%s" % (d, c["db"]))
            col_ids[(d, "QUOTE_MATERIAL_BRIDGE", c["db"])] = cid
            roles = ["PART_NAME"] if c["db"] == "material_name" else []
            vals.append("(%s,%s,%s,%s,%s,%s,%s,%d)" % (
                q(cid), q(bid), q(c["db"]), q(c["label"]), q(c["type"]),
                "TRUE" if c["is_code"] else "FALSE", arr(roles), c["sort"]))
            ncols += 1
    # 价格策略函数列（沿用 V388 声明逐字不变）
    for i, (dbc, lbl, ty) in enumerate([("unit_price", "元素单价", "MONEY"), ("currency", "货币", "TEXT")]):
        cid = uid("col:QUOTE:FUNC_ELEMENT_PRICE:%s" % dbc)
        col_ids[("QUOTE", "FUNC_ELEMENT_PRICE", dbc)] = cid
        vals.append("(%s,%s,%s,%s,%s,FALSE,'{}',%d)" % (q(cid), q(func_id), q(dbc), q(lbl), q(ty), i))
        ncols += 1

    o.append("INSERT INTO semantic_node_column (id,node_id,db_column,display_name,data_type,is_code,roles,"
             "sort_order,created_by) VALUES")
    o.append(",\n".join(v[:-1] + ",'seed')" for v in vals) + ";")
    o.append("")

    # ---- 边 ----
    o.append("-- ============ 4. 边 ============")
    o.append("-- 4.1 料号桥（S-24 / AC-111 / AC-112）：核价两套里每个**有 production_no 列**的 SHEET 节点")
    o.append("--     → ds_quote_material，LOOKUP ⇒ LEFT JOIN ON <桥>.production_no = <锚点>.production_no。")
    o.append("--     🚫 不用 GRAIN/SUB：LOOKUP 是唯一编译成 LEFT JOIN 的边种（SemanticCompiler#ensureLeftJoin），")
    o.append("--        GRAIN 会展开行数、SUB 是相关标量子查询，两者都不满足 AC-112「0 行而非报错」。")
    edge_vals = []
    key_vals = []
    bridge_edge_ids = []
    nedges = 0
    for t in graph:
        if t["dialect"] == "QUOTE":
            continue
        if "production_no" not in [c["db"] for c in t["cols_out"]]:
            continue
        eid = uid("edge:%s:%s:BRIDGE" % (t["dialect"], t["node_key"]))
        bridge_edge_ids.append((t["dialect"], t["node_key"], eid))
        # ⚠️ 这里恒发历史值 LOOKUP / MANY_TO_ONE —— V413 的 checksum 已被共享库锁死，一个字节都不能动。
        #    真值是 NARROW（V416）与 ONE_TO_MANY（V414），见文件头那四个常量的注释。
        edge_vals.append("(%s,%s,%s,'%s','%s',NULL,NULL,'NA',NULL,%s)" % (
            q(eid), q(t["id"]), q(bridge_ids[t["dialect"]]), BRIDGE_EDGE_KIND_V413, BRIDGE_CARDINALITY_V413,
            q("料号桥：生产料号 → 报价物料（拿销售料号/品名/规格）。桥无对应行时 LEFT JOIN 出 NULL，不报错")))
        key_vals.append("(%s,%s,'production_no','production_no',0)" % (
            q(uid("key:%s:0" % eid)), q(eid)))
        nedges += 1

    o.append("-- 4.2 价格策略原子组（PRICE 边，仅 QUOTE 的材质元素页签）")
    eb = next(t for t in graph if t["dialect"] == "QUOTE" and t["suffix"] == "element_bom")
    peid = uid("edge:QUOTE:ELEMENT_BOM:PRICE")
    edge_vals.append("(%s,%s,%s,'PRICE','MANY_TO_ONE',NULL,NULL,'NA',NULL,%s)" % (
        q(peid), q(eb["id"]), q(func_id),
        q("双条件 JOIN；cep.material_no 必须与 hf_part_no 表达式逐字一致（AC-1⑤）")))
    key_vals.append("(%s,%s,'element_code','element_code',0)" % (q(uid("key:%s:0" % peid)), q(peid)))
    key_vals.append("(%s,%s,'material_no','material_no',1)" % (q(uid("key:%s:1" % peid)), q(peid)))
    nedges += 1

    o.append("INSERT INTO semantic_edge (id,from_node_id,to_node_id,edge_kind,cardinality,fallback_order,"
             "coalesce_group,assert_status,assert_sample_rows,note,created_by) VALUES")
    o.append(",\n".join(v[:-1] + ",'seed')" for v in edge_vals) + ";")
    o.append("")
    o.append("INSERT INTO semantic_edge_key (id,edge_id,left_column,right_column,seq) VALUES")
    o.append(",\n".join(key_vals) + ";")
    o.append("")

    # ---- 页签视图 ----
    o.append("-- ============ 5. 页签视图（AC-106：费用类变体 8 / 7 / 15） ============")
    tv_vals = []
    tvn_vals = []
    counts = {}
    bridge_aux_tvn_ids = []
    tree_tv_ids = []          # V417 的命中面：tab_type 存了显示名的那几行（(dialect, node_key, id)）
    for t in graph:
        for (tab, vk, vl) in tab_of(t):
            # 🔒 uid 的键串 + 列取值都恒发**冻结值** tab（V413 checksum 锁死）。
            #    现行真值 tab_type_current(tab) 由 V417 原地 UPDATE 落库，id 不变。
            vid = uid("tv:%s:%s:%s" % (t["dialect"], tab, vk))
            if tab_type_current(tab) != tab:
                tree_tv_ids.append((t["dialect"], t["node_key"], vid, tab, tab_type_current(tab)))
            tv_vals.append("(%s,%s,%s,%s,%s,'{}',%s)" % (
                q(vid), q(tab), q(vk), q(vl), q(t["id"]), q(t["dialect"])))
            tvn_vals.append("(%s,%s,%s,'MAIN','{}',0)" % (
                q(uid("tvn:%s:MAIN" % vid)), q(vid), q(t["id"])))
            counts.setdefault(t["dialect"], {}).setdefault(tab, 0)
            counts[t["dialect"]][tab] += 1
            # 料号桥的 AUX 挂载 = 「桥的列出现在字段面板里、可拖成显示列」。
            # ⚠️ 恒按历史行为 True 产出（V413 checksum 锁死）；现行真值 BRIDGE_AS_AUX=False，
            #    由 V416 把这些行删掉 —— 销售料号是入参不是展示列（用户 2026-09-04 裁决）。
            if (BRIDGE_AS_AUX_V413 and t["dialect"] in bridge_ids
                    and "production_no" in [c["db"] for c in t["cols_out"]]):
                tvn_id = uid("tvn:%s:BRIDGE" % vid)
                bridge_aux_tvn_ids.append((t["dialect"], t["node_key"], tvn_id))
                tvn_vals.append("(%s,%s,%s,'AUX','{}',1)" % (
                    q(tvn_id), q(vid), q(bridge_ids[t["dialect"]])))
            if t["dialect"] == "QUOTE" and t["suffix"] == "element_bom":
                tvn_vals.append("(%s,%s,%s,'AUX','{}',1)" % (
                    q(uid("tvn:%s:PRICE" % vid)), q(vid), q(func_id)))

    for d in ("QUOTE", "COST_BASIC", "COST_DETAIL"):
        o.append("--   %-11s %s = %d 行" % (
            d, " + ".join("%s×%d" % (k, v) for k, v in counts[d].items()), sum(counts[d].values())))
    o.append("INSERT INTO semantic_tab_view (id,tab_type,variant_key,variant_label,anchor_node_id,switches,"
             "dialect,created_by) VALUES")
    o.append(",\n".join(v[:-1] + ",'seed')" for v in tv_vals) + ";")
    o.append("")
    o.append("INSERT INTO semantic_tab_view_node (id,view_id,node_id,role,add_dims,sort_order,created_by) VALUES")
    o.append(",\n".join(v[:-1] + ",'seed')" for v in tvn_vals) + ";")
    o.append("")
    o.append("-- semantic_tab_view_column（页签级角色覆盖，D-35）刻意留空：")
    o.append("--   新模型一表一 sheet，节点级 roles 已经是每个页签的正确取值，没有需要覆盖的场景。")
    o.append("")

    stats = {
        "nodes": len(node_rows), "cols": ncols, "edges": nedges,
        "views": len(tv_vals), "tvn": len(tvn_vals), "graph_tables": len(graph),
        "bridge_edge_ids": bridge_edge_ids,
        "bridge_aux_tvn_ids": bridge_aux_tvn_ids,
        "tree_tv_ids": tree_tv_ids,
    }
    o.append("-- ============ 统计（生成时计算，用于人工复核） ============")
    o.append("--   进图主表 %d 张 / 节点 %d（含 2 桥 + 1 函数）/ 列声明 %d / 边 %d / 页签视图 %d / 页签节点 %d"
             % (stats["graph_tables"], stats["nodes"], stats["cols"], stats["edges"], stats["views"], stats["tvn"]))
    return "\n".join(o) + "\n", stats, counts


# ── 生成：V414 料号桥基数修正（增量，🚫 不改 V413） ────────────────────────
def gen_card_fix(bridge_edge_ids):
    n = len(bridge_edge_ids)
    o = []
    o.append("-- V414__task260819_v9_bridge_cardinality_fix.sql")
    o.append("-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。")
    o.append("-- task-260819 · v9 · B-43 修正（AC-111 / AC-112 / AC-121）")
    o.append("--")
    o.append("-- 【改什么】%d 条料号桥边（核价锚点 → QUOTE_MATERIAL_BRIDGE）的 cardinality：" % n)
    o.append("--     MANY_TO_ONE  →  %s" % BRIDGE_CARDINALITY)
    o.append("--")
    o.append("-- 【为什么】MANY_TO_ONE 在本项目的语义是「右侧连接键在目标表里唯一」——")
    o.append("--   SemanticGraphValidator#checkEdgeCardinality 就是按这个跑断言的")
    o.append("--   （GROUP BY 右键 HAVING count(*)>1，有重复即 FAIL，提示语原文：「改成 ONE_TO_MANY，")
    o.append("--   或补一组连接键把粒度收窄到唯一」）。而右键 ds_quote_material.production_no **不唯一**：")
    o.append("--     2026-09-04 在 cpq_db_0724 实测 —— 45 行 / 25 行 production_no 非空 / 24 个不同值 /")
    o.append("--     重复组 1 组：TEST0813-P01-PROD ×2（销售料号 TEST0813-P01 与 TEST0813-P01-BD1 共用）")
    o.append("--   🚦 用户裁决（2026-09-04）：**多个销售料号共用一个生产料号是合法业务**，")
    o.append("--      不是脏数据 ⇒ 不能靠清数据让断言变绿，只能把声明改对。")
    o.append("--")
    o.append("-- 【方向】边是「核价锚点 → 桥」。锚点侧一个 production_no，桥侧可能有 N 行 ⇒")
    o.append("--   从 from 看向 to 是一对多 ⇒ ONE_TO_MANY。改完这条边不再进 MANY_TO_ONE 断言，")
    o.append("--   assert_status 归 NA（recomputeAssertStatus 对非 MANY_TO_ONE 边一律标 NA，")
    o.append("--   刻意不标 PASS —— 标 PASS 才是虚假的绿）。")
    o.append("--")
    o.append("-- 🚨 【本迁移不解决扇出】—— 声明改对 ≠ 行为改对。")
    o.append("--   SemanticCompiler 全文没有一处读 cardinality（2026-09-04 grep 实证），LOOKUP 边恒编译成")
    o.append("--   裸 LEFT JOIN ⇒ 右键重复时**锚点行会被复制**，核价行数与金额静默翻倍。")
    o.append("--   实证（只读，桥数据为共享库真实数据）：")
    o.append("--     锚点 1 行 × TEST0813-P01-PROD  →  经桥 LEFT JOIN 后 2 行")
    o.append("--     锚点 1 行 × 3110520789        →  经桥 LEFT JOIN 后 1 行")
    o.append("--   去重/聚合修法涉及 builder/compiler/**（后端 #1 的文件）或桥的建模形态，")
    o.append("--   已报主线待裁，🚫 本迁移不擅自扩范围。")
    o.append("")
    o.append("-- 按主键逐条更新（%d 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）。" % n)
    o.append("UPDATE semantic_edge SET cardinality = '%s', updated_by = 'seed', updated_at = now()," % BRIDGE_CARDINALITY)
    o.append("       assert_status = 'NA', assert_sample_rows = NULL")
    o.append(" WHERE cardinality = '%s'" % BRIDGE_CARDINALITY_V413)
    o.append("   AND id IN (")
    for i, (d, nk, eid) in enumerate(bridge_edge_ids):
        o.append("     %s'%s'%s -- %s.%s" % ("  " if i else "  ", eid, "," if i < n - 1 else "", d, nk))
    o.append("   );")
    o.append("")
    o.append("-- 落地守卫：id 对不上就**报错中止**，而不是静默更新 0 行。")
    o.append("-- （种子若被重灌成别的 UUID，静默 0 行 = 断言继续按错的基数跑 = 回到本次要修的那个坑。）")
    o.append("DO $$")
    o.append("DECLARE bad_cnt int; ok_cnt int;")
    o.append("BEGIN")
    o.append("  SELECT count(*) INTO ok_cnt FROM semantic_edge e")
    o.append("    JOIN semantic_node t ON t.id = e.to_node_id")
    o.append("   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.cardinality = '%s';" % BRIDGE_CARDINALITY)
    o.append("  SELECT count(*) INTO bad_cnt FROM semantic_edge e")
    o.append("    JOIN semantic_node t ON t.id = e.to_node_id")
    o.append("   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.cardinality <> '%s';" % BRIDGE_CARDINALITY)
    o.append("  IF ok_cnt <> %d OR bad_cnt <> 0 THEN" % n)
    o.append("    RAISE EXCEPTION '料号桥基数修正未落全：期望 %% 条 %s / 0 条其它，实得 %% / %%',"
             % BRIDGE_CARDINALITY)
    o.append("      %d, ok_cnt, bad_cnt;" % n)
    o.append("  END IF;")
    o.append("END $$;")
    return "\n".join(o) + "\n"


# ── 生成：V416 料号桥改「输入收窄」形态（增量，🚫 不改 V413/V414） ──────────
def gen_narrow(bridge_edge_ids, bridge_aux_tvn_ids):
    ne, na = len(bridge_edge_ids), len(bridge_aux_tvn_ids)
    o = []
    o.append("-- V416__task260819_v9_bridge_narrow_form.sql")
    o.append("-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。")
    o.append("-- task-260819 · v9 · B-43 形态改造（用户 2026-09-04 裁决）")
    o.append("--")
    o.append("-- 【改什么】")
    o.append("--   ① %d 条料号桥边 edge_kind：%s → %s" % (ne, BRIDGE_EDGE_KIND_V413, BRIDGE_EDGE_KIND))
    o.append("--   ② 删掉 %d 行桥的 AUX 挂载（semantic_tab_view_node）—— 桥不再是可拖的数据源" % na)
    o.append("--")
    o.append("-- 【为什么】桥的用法整个反了。同一张表、同一列，用在 SELECT 里还是 WHERE 里，")
    o.append("--   差别就是扇出与不扇出：")
    o.append("--     ❌ 旧：LEFT JOIN ds_quote_material ON dqm.production_no = 锚点.production_no")
    o.append("--        方向 生产料号 → 销售料号。实测 45 行 / 24 个不同 production_no / 重复组 1")
    o.append("--        （TEST0813-P01-PROD 对应 TEST0813-P01 与 TEST0813-P01-BD1 两个销售料号）")
    o.append("--        ⇒ 锚点行被复制，核价行数与金额**静默翻倍**。端到端实测：1 行 → 2 行。")
    o.append("--     ✅ 新：WHERE 锚点.production_no IN (SELECT production_no FROM ds_quote_material")
    o.append("--                                        WHERE material_no = ANY(:total_material_no))")
    o.append("--        方向 销售料号 → 生产料号。实测 45 行 / 45 个不同 material_no / 重复组 0")
    o.append("--        ⇒ 半连接谓词，**永不扇出**。")
    o.append("--   业务口径（用户原话）：「核价页签上不用显示销售料号，销售料号在产品卡片上，")
    o.append("--   是产品卡片的基础属性」「核价是按照报价侧提供的 产品卡片销售料号 → 找到对应的")
    o.append("--   生产料号 → 展示该生产料号的 BOM」⇒ 销售料号是**入参**，不是展示列。")
    o.append("--")
    o.append("-- 【为什么必须新造 edge_kind】现有四种边**都表达不了**这件事（2026-09-04 逐点核实）：")
    o.append("--   LOOKUP/JOIN → 产 FROM 项（LEFT/INNER JOIN），会扇出；")
    o.append("--   SUB         → 产的是相关标量子查询，是**列表达式**不是 WHERE 谓词；")
    o.append("--   GRAIN       → 按定义就是展开行数。")
    o.append("--   而 semantic_node.fixed_predicate 只在**边的目标节点**上被消费")
    o.append("--   （SemanticCompiler 第 328 / 499 行，都是拼进 JOIN 的 ON 子句），锚点侧不读。")
    o.append("--   ⇒ 契约新增取值 '%s'：拿 from 表的键解析出 to 表的键，用结果收窄 from 表；" % BRIDGE_EDGE_KIND)
    o.append("--     产物是 WHERE 半连接谓词，🚫 不产 FROM 项、🚫 不产显示列、🚫 不进字段面板。")
    o.append("--     semantic_edge 的**列结构不动**，只是多一个取值。")
    o.append("--     编译器侧的 case \"%s\" 分支由后端 #1 并行实现；本迁移只负责边的数据。" % BRIDGE_EDGE_KIND)
    o.append("--")
    o.append("-- 📌 V414（把这 28 条边改成 %s）自此**无实际意义**（没有 JOIN 就没有基数问题），" % BRIDGE_CARDINALITY)
    o.append("--   但它已应用共享库，🚫 不撤销、不改号，留着即可。")
    o.append("")
    o.append("-- ① 边形态：按主键逐条更新（%d 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）" % ne)
    o.append("UPDATE semantic_edge SET edge_kind = '%s', updated_by = 'seed', updated_at = now()," % BRIDGE_EDGE_KIND)
    o.append("       assert_status = 'NA', assert_sample_rows = NULL,")
    o.append("       note = '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，"
             "不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。'")
    o.append(" WHERE edge_kind = '%s'" % BRIDGE_EDGE_KIND_V413)
    o.append("   AND id IN (")
    for i, (d, nk, eid) in enumerate(bridge_edge_ids):
        o.append("     '%s'%s -- %s.%s" % (eid, "," if i < ne - 1 else "", d, nk))
    o.append("   );")
    o.append("")
    o.append("-- ② 摘掉桥的 AUX 挂载：字段面板据此列出可拖数据源，删掉它 = 销售料号不再出现在核价页签")
    o.append("DELETE FROM semantic_tab_view_node WHERE id IN (")
    for i, (d, nk, tid) in enumerate(bridge_aux_tvn_ids):
        o.append("     '%s'%s -- %s.%s" % (tid, "," if i < na - 1 else "", d, nk))
    o.append("   );")
    o.append("")
    o.append("-- 落地守卫：对不上就**报错中止**，而不是静默更新/删除 0 行。")
    o.append("DO $$")
    o.append("DECLARE e_ok int; e_bad int; aux_left int;")
    o.append("BEGIN")
    o.append("  SELECT count(*) INTO e_ok  FROM semantic_edge e JOIN semantic_node t ON t.id = e.to_node_id")
    o.append("   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.edge_kind = '%s';" % BRIDGE_EDGE_KIND)
    o.append("  SELECT count(*) INTO e_bad FROM semantic_edge e JOIN semantic_node t ON t.id = e.to_node_id")
    o.append("   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.edge_kind <> '%s';" % BRIDGE_EDGE_KIND)
    o.append("  SELECT count(*) INTO aux_left FROM semantic_tab_view_node tvn")
    o.append("    JOIN semantic_node n ON n.id = tvn.node_id")
    o.append("   WHERE n.node_key = 'QUOTE_MATERIAL_BRIDGE';")
    o.append("  IF e_ok <> %d OR e_bad <> 0 OR aux_left <> 0 THEN" % ne)
    o.append("    RAISE EXCEPTION '料号桥形态改造未落全：期望 %% 条 %s 边 / 0 条其它 / 0 行 AUX 挂载，"
             "实得 %% / %% / %%', %d, e_ok, e_bad, aux_left;" % (BRIDGE_EDGE_KIND, ne))
    o.append("  END IF;")
    o.append("END $$;")
    return "\n".join(o) + "\n"


# ── 生成：V417 页签类型键值列修正（增量，🚫 不改 V413） ──────────────────────
def gen_tab_type_fix(tree_tv_ids):
    """把 semantic_tab_view.tab_type 里被写成**显示名**的行改回**存储值**（D-39 / B-54）。

    🚫 不改 V413：它已应用共享库、checksum 被 Flyway 锁死。与 V414 / V416 同一手法 ——
       按 UUIDv5 主键逐条 UPDATE，🚫 不发无 WHERE 的全表 UPDATE（CLAUDE.md §3.2）。
    """
    n = len(tree_tv_ids)
    assert n > 0, "tree_tv_ids 为空 —— 冻结值与现行真值已无差异？那本迁移就不该再生成"
    frozen = sorted({r[3] for r in tree_tv_ids})
    target = sorted({r[4] for r in tree_tv_ids})
    assert len(frozen) == 1 and len(target) == 1, \
        "本迁移只处理单一取值的改名；出现多组时请扩写模板（frozen=%s target=%s)" % (frozen, target)
    old_v, new_v = frozen[0], target[0]

    # 主键列表（两处复用：UPDATE 的 WHERE 与守卫的计数）
    id_lines = ["     %s%s -- %s.%s" % (q(vid), "," if i < n - 1 else "", d, nk)
                for i, (d, nk, vid, _f, _t) in enumerate(tree_tv_ids)]

    o = []
    o.append("-- V417__task260819_v9_tab_type_key_value_fix.sql")
    o.append("-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。")
    o.append("-- task-260819 · v9 · B-54（需求文档 D-39，2026-08-21 裁决；用户 2026-09-05 批准本次修复）")
    o.append("--")
    o.append("-- 【改什么】semantic_tab_view.tab_type：'%s' → '%s'，共 %d 行（三个方言各 1 行）。"
             % (old_v, new_v, n))
    o.append("--")
    o.append("-- 【为什么】D-39 原文把两件东西分得很清楚：")
    o.append("--     · 存储值 = '%s'     —— component.tab_type / ComponentService.VALID_TAB_TYPES /" % new_v)
    o.append("--                            semantic_tab_view.tab_type / builder_config.tabType，四处统一")
    o.append("--     · 显示名 = 「%s」   —— Select 的 label、图谱页文案、文档正文" % old_v)
    o.append("--   而 V413 种子把**显示名写进了键值列**。后果不是显示难看，是**存不进去**：")
    o.append("--     PUT /api/cpq/components/{id}/builder   tabType = '%s'" % old_v)
    o.append("--       → 400 Invalid tabType: %s. Must be one of: [费用类,外购件,零件,BOM,主件,材质元素]" % old_v)
    o.append("--     ⇒ 用取数配置器配「%s」页签的**新组件根本建不出来**。" % old_v)
    o.append("--")
    o.append("-- 🚨 这是**同一缺陷的第二次**。D-39 的「起因」栏记的就是第一次：前端把 TAB_TYPES 写成")
    o.append("--   '%s'，includes() 静默 miss，存量 tab_type='%s' 的组件打开取数配置 Tab 被误初始化" % (old_v, new_v))
    o.append("--   成「主件」。D-39 就是为防它而写的裁决，然后种子这一侧犯了镜像版本的同一个错。")
    o.append("--   ⇒ 光有文档裁决拦不住第三次，故本次同时加**启动期自检**（B-56）")
    o.append("--     com.cpq.semanticgraph.service.SemanticGraphKeyValueSelfCheck：")
    o.append("--     semantic_tab_view.tab_type ⊄ ComponentService.VALID_TAB_TYPES 时**服务直接起不来**。")
    o.append("--")
    o.append("-- 【存量口径站在 '%s' 一边】实测 component.tab_type 分布：" % new_v)
    o.append("--   BOM=21 / 主件=36 / 材质元素=22 / 零件=15 / 外购件=15 / NULL=186；'%s' **0 行**。" % old_v)
    o.append("--   ⇒ 该改的是种子，不是 VALID_TAB_TYPES（D-39 明写「现网已有该值的数据，不可改」）。")
    o.append("--")
    o.append("-- 【命中面】%d 行，可逆（反向 UPDATE 即还原）。🚫 不删行、🚫 不动 id ——" % n)
    o.append("--   id 是 UUIDv5（键串里含冻结的 '%s'），semantic_tab_view_node 有 FK 指着它。" % old_v)
    o.append("-- 【明确不动的行】零件 / 外购件 / 主件 / 材质元素 / 费用类 一律不碰：它们两边逐字一致，")
    o.append("--   且现网 30 个存量组件靠「零件 / 外购件」两行打开配置页（task-260904 S-4 划的边界）。")
    o.append("")
    o.append("-- ① 落地前守卫：目标值不能已被占用 —— 唯一键是 (tab_type, variant_key, dialect)，")
    o.append("--    改 tab_type 就是在动唯一键，撞了要给出说得清的报错，而不是一句裸 23505。")
    o.append("DO $$")
    o.append("DECLARE clash int;")
    o.append("BEGIN")
    o.append("  SELECT count(*) INTO clash FROM semantic_tab_view v")
    o.append("   WHERE v.tab_type = " + q(new_v))
    o.append("     AND EXISTS (SELECT 1 FROM semantic_tab_view s")
    o.append("                  WHERE s.tab_type = " + q(old_v) + " AND s.variant_key = v.variant_key")
    o.append("                    AND s.dialect = v.dialect);")
    o.append("  IF clash > 0 THEN")
    o.append("    RAISE EXCEPTION 'V417 撞唯一键 (tab_type,variant_key,dialect)：已有 % 行 tab_type="
             + new_v + " 与待改行的 (variant_key,dialect) 重合。🚫 不要强改，先人工核对这些行是谁写的。', clash;")
    o.append("  END IF;")
    o.append("END $$;")
    o.append("")
    o.append("-- ② 按主键逐条更新（%d 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）" % n)
    o.append("UPDATE semantic_tab_view")
    o.append("   SET tab_type = " + q(new_v) + ", updated_by = 'seed', updated_at = now()")
    o.append(" WHERE tab_type = " + q(old_v))
    o.append("   AND id IN (")
    o.extend(id_lines)
    o.append("   );")
    o.append("")
    o.append("-- ③ 落地守卫：断言**状态**而不是变化量（RECORD「断言状态而非变化量」）——")
    o.append("--    对不上就报错中止，🚫 不允许静默改 0 行。")
    o.append("DO $$")
    o.append("DECLARE fixed int; leftover int; domain_now text;")
    o.append("BEGIN")
    o.append("  -- ③-a 点名的那 %d 行，现在必须都是 '%s'" % (n, new_v))
    o.append("  SELECT count(*) INTO fixed FROM semantic_tab_view")
    o.append("   WHERE tab_type = " + q(new_v) + " AND id IN (")
    o.extend(id_lines)
    o.append("   );")
    o.append("  -- ③-b 全表不允许再有 '%s' 残留（含本次没点名的行，防漏网）" % old_v)
    o.append("  SELECT count(*) INTO leftover FROM semantic_tab_view WHERE tab_type = " + q(old_v) + ";")
    o.append("  IF fixed <> %d OR leftover <> 0 THEN" % n)
    o.append("    SELECT string_agg(DISTINCT tab_type, ' | ' ORDER BY tab_type) INTO domain_now")
    o.append("      FROM semantic_tab_view;")
    o.append("    RAISE EXCEPTION 'V417 未落全：期望 " + str(n) + " 行 tab_type=" + new_v
             + " / 0 行 " + old_v + " 残留，实得 % / %。当前 tab_type 值域=[%]', fixed, leftover, domain_now;")
    o.append("  END IF;")
    o.append("  RAISE NOTICE 'V417 ✅ semantic_tab_view.tab_type：% 行改为 " + new_v
             + "，全表无 " + old_v + " 残留', fixed;")
    o.append("END $$;")
    return "\n".join(o) + "\n"


def main():
    check = "--check" in sys.argv
    text = MATRIX.read_text(encoding="utf-8")
    tables = [derive(t) for t in parse_matrix(text)]

    assert len(tables) == 45, "字段矩阵应解析出 45 张主表，实得 %d" % len(tables)

    # 别名字节长度守卫（R9）：PG 标识符上限 63 字节，超限静默截断
    worst = ("", 0)
    for t in tables:
        if t["dialect"] != "QUOTE":
            continue
        for c in t["cols_out"]:
            alias = "_%s_%s" % (t["cn"], c["label"])
            n = len(alias.encode("utf-8"))
            if n > worst[1]:
                worst = (alias, n)
    assert worst[1] < 63, "QUOTE 列别名超 PG 63 字节上限（会被静默截断）：%s = %d 字节" % worst

    sql_views = gen_views(tables)
    sql_seed, stats, counts = gen_seed(tables)
    sql_card = gen_card_fix(stats["bridge_edge_ids"])
    sql_narrow = gen_narrow(stats["bridge_edge_ids"], stats["bridge_aux_tvn_ids"])
    sql_tabtype = gen_tab_type_fix(stats["tree_tv_ids"])
    assert len(stats["tree_tv_ids"]) == 3, \
        "页签类型键值修正应命中 3 行（三方言各 1 行 BOM 树），实得 %d" % len(stats["tree_tv_ids"])
    assert len(stats["bridge_aux_tvn_ids"]) == 32, \
        "桥的 AUX 挂载应为 32 行（COST_BASIC 12 + COST_DETAIL 20），实得 %d" % len(stats["bridge_aux_tvn_ids"])
    assert len(stats["bridge_edge_ids"]) == 28, \
        "料号桥边应为 28 条（COST_BASIC 10 + COST_DETAIL 18），实得 %d" % len(stats["bridge_edge_ids"])

    if check:
        ok = True
        for path, content in ((OUT_VIEWS, sql_views), (OUT_SEED, sql_seed), (OUT_CARD_FIX, sql_card),
                              (OUT_NARROW, sql_narrow), (OUT_TAB_TYPE, sql_tabtype)):
            new = hashlib.md5(content.encode("utf-8")).hexdigest()
            old = hashlib.md5(path.read_bytes()).hexdigest() if path.exists() else "(缺失)"
            same = new == old
            ok = ok and same
            print("%-70s 仓库=%s 重跑=%s %s" % (path.name, old, new, "一致 ✅" if same else "不一致 ❌"))
        sys.exit(0 if ok else 1)

    OUT_VIEWS.write_text(sql_views, encoding="utf-8")
    OUT_SEED.write_text(sql_seed, encoding="utf-8")
    OUT_CARD_FIX.write_text(sql_card, encoding="utf-8")
    OUT_NARROW.write_text(sql_narrow, encoding="utf-8")
    OUT_TAB_TYPE.write_text(sql_tabtype, encoding="utf-8")
    print("解析主表 %d 张；进图 %d 张" % (len(tables), stats["graph_tables"]))
    print("料号桥边 %d 条：V413 发历史值 %s，V414 增量改成 %s"
          % (len(stats["bridge_edge_ids"]), BRIDGE_CARDINALITY_V413, BRIDGE_CARDINALITY))
    print("最长 QUOTE 列别名：%s（%d 字节，上限 63）" % worst)
    for d in ("QUOTE", "COST_BASIC", "COST_DETAIL"):
        print("  %-11s %s" % (d, dict(counts[d])))
    print("节点 %d / 列 %d / 边 %d / 页签视图 %d / 页签节点 %d"
          % (stats["nodes"], stats["cols"], stats["edges"], stats["views"], stats["tvn"]))
    print("料号桥形态：V413 发历史值 %s + AUX 挂载 %d 行，V416 增量改成 %s + 摘掉挂载"
          % (BRIDGE_EDGE_KIND_V413, len(stats["bridge_aux_tvn_ids"]), BRIDGE_EDGE_KIND))
    print("页签类型键值：V413 发历史值 %s（错，显示名进了键值列）%d 行，V417 增量改成 %s"
          % (TAB_TYPE_TREE_V413, len(stats["tree_tv_ids"]), TAB_TYPE_TREE))
    print("写出：\n  %s\n  %s\n  %s\n  %s\n  %s"
          % (OUT_VIEWS, OUT_SEED, OUT_CARD_FIX, OUT_NARROW, OUT_TAB_TYPE))
    for path in (OUT_VIEWS, OUT_SEED, OUT_CARD_FIX, OUT_NARROW, OUT_TAB_TYPE):
        print("  md5 %s  %s" % (hashlib.md5(path.read_bytes()).hexdigest(), path.name))


if __name__ == "__main__":
    main()
