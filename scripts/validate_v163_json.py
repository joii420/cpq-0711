"""Static validator for V163 seed SQL.

Parses every $JSON$...$JSON$ / $PA$...$PA$ / $SF$...$SF$ dollar-quoted block
and verifies each is well-formed JSON. Also dumps row/column counts so we can
cross-check the PART G expected values without running the migration.

Run: python scripts/validate_v163_json.py
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

SQL = pathlib.Path(__file__).resolve().parents[1] / (
    "cpq-backend/src/main/resources/db/migration/V163__seed_configure_product_template.sql"
)

TAGS = ("JSON", "PA", "SF")


def main() -> int:
    src = SQL.read_text(encoding="utf-8")
    ok = fail = 0
    counts: dict[str, list[int]] = {t: [] for t in TAGS}
    for tag in TAGS:
        pattern = re.compile(rf"\${tag}\$(.+?)\${tag}\$", re.DOTALL)
        for m in pattern.finditer(src):
            body = m.group(1)
            try:
                val = json.loads(body)
            except json.JSONDecodeError as e:
                print(f"  FAIL ${tag}$  {e}")
                print("    head:", body[:160].replace("\n", " "))
                fail += 1
                continue
            n = len(val) if isinstance(val, list) else 1
            kind = "array" if isinstance(val, list) else type(val).__name__
            counts[tag].append(n)
            print(f"  OK   ${tag}$  ({kind}, items={n})")
            ok += 1

    print()
    print(f"Total: {ok} OK / {fail} FAIL")
    print()
    print("By tag (item counts per literal):")
    for tag in TAGS:
        print(f"  ${tag}$ : {counts[tag]}")

    # Cross-check expectations
    print()
    print("Cross-check vs PART G (expected):")
    # 7 components × 1 fields literal each = 7 ${JSON}$ blocks for components
    # + 1 ${JSON}$ for excel_view_config = 8 total
    # PA and SF: 1 each (product_attributes / subtotal_formula)
    j_cnt = len(counts["JSON"])
    print(f"  $JSON$ blocks: {j_cnt}  (expected 8 = 7 components + 1 excel_view_config)")
    print(f"  $PA$   blocks: {len(counts['PA'])}  (expected 1)")
    print(f"  $SF$   blocks: {len(counts['SF'])}  (expected 1)")

    # The excel_view_config block has 21 cols; spot check
    if counts["JSON"]:
        evc_cols = counts["JSON"][-1]
        print(f"  Last $JSON$ (= excel_view_config) item count: {evc_cols} (expected 21)")

    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
