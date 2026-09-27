#!/usr/bin/env python3
"""Generate contract fixtures for S-API-2 without touching any database."""

from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path

from openpyxl import Workbook


HEADERS = [
    "客户编号",
    "客户名称",
    "客户等级",
    "所属行业",
    "产品分类",
    "所属区域",
    "地址",
    "信用额度",
    "付款方式",
    "备注",
    "联系人姓名",
    "联系人职务",
    "联系人电话",
    "联系人邮箱",
    "联系人微信",
]

PREFIX = "IMP-260926-"


def write_book(path: Path, rows: list[list[object]], headers: list[str] | None = None) -> None:
    wb = Workbook()
    ws = wb.active
    ws.title = "客户"
    ws.append(headers or HEADERS)
    for row in rows:
        ws.append(row)
    wb.save(path)


def valid_row(suffix: str, *, code: str | None = None, name: str | None = None) -> list[object]:
    return [
        code if code is not None else f"{PREFIX}{suffix}",
        name if name is not None else f"导入客户-{PREFIX}{suffix}",
        "STANDARD",
        "0001",
        "000000",
        "华东",
        "上海",
        "100000.00",
        "月结",
        f"S-API-2 fixture {suffix}",
        f"联系人-{suffix}",
        "采购",
        "13800138000",
        f"{suffix.lower()}@example.com",
        f"wx-{suffix.lower()}",
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--run-id", help="unique suffix so repeated runs never reuse database identifiers")
    args = parser.parse_args()
    global PREFIX
    run_id = args.run_id or datetime.now().strftime("%m%d%H%M%S%f")
    PREFIX = f"IMP-260926-{run_id}-"
    args.output.mkdir(parents=True, exist_ok=True)

    write_book(
        args.output / "valid_two_rows.xlsx",
        [valid_row("V01"), valid_row("V02", code="")],
    )
    write_book(
        args.output / "existing_code_mix.xlsx",
        [
            # Run after valid_two_rows.xlsx so this code is an existing record.
            valid_row("CONFLICT", code=f"{PREFIX}V01"),
            valid_row("SHOULD-NOT-WRITE"),
        ],
    )
    write_book(
        args.output / "duplicate_in_file.xlsx",
        [
            valid_row("DUP-A", code=f"{PREFIX}CASE", name=f"{PREFIX}Duplicate Name"),
            valid_row("DUP-B", code=f"{PREFIX.lower()}case", name=f"{PREFIX.lower()}duplicate name"),
        ],
    )
    too_long_code = "C" * 51
    too_long_name = "N" * 201
    errors = valid_row("ERROR-BASE")
    errors[1] = ""
    errors[10] = ""
    errors[12] = "123"
    errors[2] = "NOT-A-LEVEL"
    errors[3] = "UNKNOWN-INDUSTRY"
    errors[4] = "UNKNOWN-CATEGORY"
    write_book(
        args.output / "multiple_errors.xlsx",
        [
            errors,
            valid_row("ERROR-LENGTH", code=too_long_code, name=too_long_name),
        ],
    )
    write_book(args.output / "header_only.xlsx", [])
    write_book(
        args.output / "wrong_header.xlsx",
        [valid_row("WRONG-HEADER")],
        headers=HEADERS[:-1] + ["不支持字段"],
    )
    (args.output / "damaged.xlsx").write_bytes(b"not-a-zip-xlsx")
    (args.output / "not_xlsx.xls").write_text("not an xlsx workbook", encoding="utf-8")
    print(f"generated {len(list(args.output.iterdir()))} fixtures under {args.output}")


if __name__ == "__main__":
    main()
