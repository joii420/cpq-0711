#!/usr/bin/env python3
"""
探查（非 AC 用例，只为给主线提供 AC-8 断言前提的事实）：
  复制单里带过来的正式账（源单 0881 的旧卡片值）≠ 复制后重算出来的正式账？
  若是，则 AC-8 第 3 步「col_1/col_2 与 V1 相同」「V2.col_3 > V1.col_3」的前提（V1 = 改值前的判定值）在复制单上不成立，
  与被测改动无关（判定值来自卡片引擎，本任务不改卡片引擎）。
步骤（全部在本片私有复制单 P 上）：
  a. 复制 0881 得 P → ensure-card-values → 取判定值 A（复制带来的正式账）
  b. P 行正式账置 NULL → ensure-card-values → 取判定值 B（重算的正式账，未改任何输入）
  c. quote-card-edit 组装加工费.加工费 = 原值（39.54034733，不改值只触发重算）→ 判定值 C
  d. quote-card-edit 组装加工费.加工费 = 50 → 判定值 D
  finally 删除 P。
用法：BASE=http://localhost:8081 OUT_DIR=<新目录> python3 probe_ac8_baseline.py
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault('MASTER', os.environ.get('BASE', ''))
import run_s2 as S

C = 'PROBE-AC8'


def orc(line, tj):
    cv = S.card_text(line)
    return [S.oracle(cv, c) for c in tj] if S.card_usable(cv) else f'不可用:{cv[:60]}'


def main():
    b = S.Api(S.BASE, f'BASE({S.BASE})')
    try:
        p = S.copy_quote(b, C, S.SRC_0881, 'P(0881,AC-8前提探查)')
        S.ensure_card(b, C, p)
        line = S.lines_of(p)[0][0]
        st, v = S.excel_view(b, C, p, '取列')
        tj = S.tj_cols(v['columns'])
        A = orc(line, tj); S.log(C, f'  A 复制带来的正式账判定值 = {A}'); print('A', A)
        S.guarded_update(C, line, 'quote_card_values = NULL')
        S.ensure_card(b, C, p)
        B = orc(line, tj); S.log(C, f'  B 置空后重算的判定值 = {B}'); print('B', B)
        tab = next(t for t in json.loads(S.card_text(line))['tabs'] if t.get('componentId') == S.ASM_FEE_CID)
        rk = (tab.get('editRows') or tab.get('formulaResults'))[0]['rowKey']
        for label, val in (('C 加工费=原值39.54034733', '39.54034733'), ('D 加工费=50', '50')):
            body = {'componentId': S.ASM_FEE_CID, 'rowKey': rk, 'fieldName': '加工费', 'value': val}
            st, j = b.call('PUT', f'/api/cpq/quotations/line-items/{line}/quote-card-edit', body)
            S.log(C, f'  [PUT quote-card-edit] {json.dumps(body, ensure_ascii=False)} -> {st}')
            X = orc(line, tj); S.log(C, f'  {label} 判定值 = {X}'); print(label, X)
    finally:
        S.cleanup(b)


if __name__ == '__main__':
    main()
