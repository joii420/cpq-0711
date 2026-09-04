#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
task-260819 · v9 · B-42 / B-43 / B-44①  语义图种子 + 全版本视图  生成器（D-82）

唯一输入： dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md
           （由 task-260902 的 scratchpad/gen_matrix.py 从三份 Excel 机器生成）

产出（两份迁移，均为纯生成物，🚫 不要手改）：
  1) V409__task260819_v9_cost_all_version_views.sql
       核价两套 26 张带版本表的 v_<主表>_all 全版本视图（S-31 / D-84 / AC-109 / AC-125）
  2) V410__task260819_v9_semantic_graph_reseed.sql
       删 V6 语义图种子 + 灌 41 张进图主表的节点/列/边/三套页签视图（S-23 / AC-102~AC-106）
       + 料号桥 LOOKUP 节点与边（S-24 / AC-111 / AC-112）

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
OUT_VIEWS = MIGDIR / "V409__task260819_v9_cost_all_version_views.sql"
OUT_SEED = MIGDIR / "V410__task260819_v9_semantic_graph_reseed.sql"

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
        return [("零件", "", None), ("外购件", "", None), ("BOM 树", "", None)]
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
    out.append("-- V409__task260819_v9_cost_all_version_views.sql")
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
    o.append("-- V410__task260819_v9_semantic_graph_reseed.sql")
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
    nedges = 0
    for t in graph:
        if t["dialect"] == "QUOTE":
            continue
        if "production_no" not in [c["db"] for c in t["cols_out"]]:
            continue
        eid = uid("edge:%s:%s:BRIDGE" % (t["dialect"], t["node_key"]))
        edge_vals.append("(%s,%s,%s,'LOOKUP','MANY_TO_ONE',NULL,NULL,'NA',NULL,%s)" % (
            q(eid), q(t["id"]), q(bridge_ids[t["dialect"]]),
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
    for t in graph:
        for (tab, vk, vl) in tab_of(t):
            vid = uid("tv:%s:%s:%s" % (t["dialect"], tab, vk))
            tv_vals.append("(%s,%s,%s,%s,%s,'{}',%s)" % (
                q(vid), q(tab), q(vk), q(vl), q(t["id"]), q(t["dialect"])))
            tvn_vals.append("(%s,%s,%s,'MAIN','{}',0)" % (
                q(uid("tvn:%s:MAIN" % vid)), q(vid), q(t["id"])))
            counts.setdefault(t["dialect"], {}).setdefault(tab, 0)
            counts[t["dialect"]][tab] += 1
            # 核价两套：主源之外把料号桥挂成附属源，字段面板才拖得到销售料号/品名
            if t["dialect"] in bridge_ids and "production_no" in [c["db"] for c in t["cols_out"]]:
                tvn_vals.append("(%s,%s,%s,'AUX','{}',1)" % (
                    q(uid("tvn:%s:BRIDGE" % vid)), q(vid), q(bridge_ids[t["dialect"]])))
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
    }
    o.append("-- ============ 统计（生成时计算，用于人工复核） ============")
    o.append("--   进图主表 %d 张 / 节点 %d（含 2 桥 + 1 函数）/ 列声明 %d / 边 %d / 页签视图 %d / 页签节点 %d"
             % (stats["graph_tables"], stats["nodes"], stats["cols"], stats["edges"], stats["views"], stats["tvn"]))
    return "\n".join(o) + "\n", stats, counts


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

    if check:
        ok = True
        for path, content in ((OUT_VIEWS, sql_views), (OUT_SEED, sql_seed)):
            new = hashlib.md5(content.encode("utf-8")).hexdigest()
            old = hashlib.md5(path.read_bytes()).hexdigest() if path.exists() else "(缺失)"
            same = new == old
            ok = ok and same
            print("%-70s 仓库=%s 重跑=%s %s" % (path.name, old, new, "一致 ✅" if same else "不一致 ❌"))
        sys.exit(0 if ok else 1)

    OUT_VIEWS.write_text(sql_views, encoding="utf-8")
    OUT_SEED.write_text(sql_seed, encoding="utf-8")
    print("解析主表 %d 张；进图 %d 张" % (len(tables), stats["graph_tables"]))
    print("最长 QUOTE 列别名：%s（%d 字节，上限 63）" % worst)
    for d in ("QUOTE", "COST_BASIC", "COST_DETAIL"):
        print("  %-11s %s" % (d, dict(counts[d])))
    print("节点 %d / 列 %d / 边 %d / 页签视图 %d / 页签节点 %d"
          % (stats["nodes"], stats["cols"], stats["edges"], stats["views"], stats["tvn"]))
    print("写出：\n  %s\n  %s" % (OUT_VIEWS, OUT_SEED))
    for path in (OUT_VIEWS, OUT_SEED):
        print("  md5 %s  %s" % (hashlib.md5(path.read_bytes()).hexdigest(), path.name))


if __name__ == "__main__":
    main()
