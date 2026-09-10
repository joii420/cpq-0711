#!/usr/bin/env bash
# repair-260910 · **AC-15 自检证据采集器**
#
# AC-15 原文：
#   后端 `mvnw test` 相关测试类全绿（surefire 报告，🚫 不看 `-q` 输出尾巴）；
#   前端 `tsc --noEmit` 0 错误；配置改动前后 `sql_template` 的 md5 已记入还原点文档。
#
# 🚨 三条纪律：
#  ① surefire 判绿一律读 `target/surefire-reports/*.txt` 的 Tests run 汇总，
#     🚫 不看 mvn 的 `-q` 输出尾巴（那行在跳过/未执行时长得和"全部通过"一模一样）。
#  ② `mvnw` 在 `cpq-backend/` 不在仓库根；**必须在 worktree 里跑**，
#     cd 到主仓跑会测错树、报假绿（cpq-worktree-maven-test-tree）。
#  ③ 全量 `mvnw test` 在本库**不可能全绿**（mat_* 表从未创建，25 个夹具文件 235 处红）——
#     🚫 不许把"全量全绿"当验收门槛。本脚本默认只跑与本次改动相关的测试类。
#
# 用法：
#   bash e2e/r260910-selfcheck.sh                 # 跑 tsc + 相关后端测试 + 配置 md5
#   R260910_TEST_PATTERN='CostingVersion*Test' bash e2e/r260910-selfcheck.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/dev-docs/task-260909-核价树骨架分档与轴口径统一/repair-260910-核价树版本切换查V6老表/证据/e2e-s1"
mkdir -p "$OUT"
F="$OUT/50-AC15-自检证据.txt"
PATTERN="${R260910_TEST_PATTERN:-*CostingVersion*Test,*VersionOption*Test,*BomTree*Test}"

# 🚨 越界防线：确认自己确实在 worktree 里（派工书 §a：所有读写都在 worktree 路径内）
case "$ROOT" in
  *"/.claude/worktrees/repair-260910-tree-version") : ;;
  *) echo "🚨 拒绝执行：当前推导出的仓库根是 $ROOT，不是 repair-260910 的 worktree。"; \
     echo "   在主工作区跑 mvnw test 会测错树并可能污染共享环境 —— 停下来报主线。"; exit 2 ;;
esac

{
  echo "# repair-260910 · AC-15 自检证据"
  echo "时刻: $(date -Is)"
  echo "仓库根: $ROOT"
  echo

  echo "## [S1] 前端 tsc --noEmit（期望 0 错误）"
  ( cd "$ROOT/cpq-frontend" && npx tsc --noEmit 2>&1 | tail -40 ) || true
  ( cd "$ROOT/cpq-frontend" && npx tsc --noEmit >/dev/null 2>&1 ) \
    && echo "tsc_result=PASS(0 错误)" || echo "tsc_result=FAIL(见上方输出)"
  echo

  echo "## [S2] 后端相关测试类（pattern=$PATTERN）"
  echo "⚠️ 判绿只看 surefire 报告，不看 mvn 输出尾巴"
  ( cd "$ROOT/cpq-backend" && ./mvnw -Dtest="$PATTERN" -DfailIfNoSpecifiedTests=false test 2>&1 | tail -30 ) || true
  echo
  echo "### surefire 汇总（唯一判据）"
  if compgen -G "$ROOT/cpq-backend/target/surefire-reports/*.txt" > /dev/null; then
    grep -h "Tests run" "$ROOT"/cpq-backend/target/surefire-reports/*.txt || echo "(报告里没有 Tests run 行)"
    echo "--- 报告文件清单 ---"
    ls -1 "$ROOT"/cpq-backend/target/surefire-reports/*.txt
  else
    echo "🚨 没有生成任何 surefire 报告 ⇒ **一个测试都没执行**。"
    echo "   这与「全部通过」在 mvn 输出上长得一模一样，属假绿第一类 —— 报主线确认测试类名。"
  fi
  echo

  echo "## [S3] 骨架配置 md5 还原点（AC-15 第三项）"
  export PGPASSWORD="${PW_DB_PASS:-joii5231}"
  psql -h "${PW_DB_HOST:-10.177.152.12}" -U "${PW_DB_USER:-postgres}" -d "${PW_DB:-cpq_db_0724}" -X -P pager=off -A -F'|' \
    -c "SELECT usage, id, name, is_active, md5(sql_template), length(sql_template), updated_at FROM costing_bom_tree_config ORDER BY usage, created_at;"
  echo
  echo "改动前基线（证据/e2e-s1/基线-改动前.txt [B1]）:"
  echo "  COST_BASIC md5 = 0b69d87d5c0a2755ac0db2c64a5bad8c"
  echo "  QUOTE      md5 = 1c089ee61054d18b9151c8f03d23bacb   ← AC-14 要求一字节未动"
  echo
  echo "## [S4] 还原点文档是否已由主线写入"
  RP="$ROOT/dev-docs/task-260909-核价树骨架分档与轴口径统一/repair-260910-核价树版本切换查V6老表/证据/配置动作-还原点.md"
  if [ -s "$RP" ]; then echo "存在且非空: $RP ($(wc -c < "$RP") 字节)"; else echo "🚨 缺失或为空: $RP —— AC-15 第三项不达（主线的交付项）"; fi
} 2>&1 | tee "$F"

echo
echo "✅ 自检证据已落盘: $F"
