#!/usr/bin/env python3
"""Generate six standalone 16:9 SVG highlight cards for the CPQ whitepaper."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "images" / "系统亮点单图"


def line_text(lines: list[str], x: int, y: int, gap: int = 43, class_name: str = "body") -> str:
    return "\n".join(
        f'<text x="{x}" y="{y + index * gap}" class="{class_name}">{line}</text>'
        for index, line in enumerate(lines)
    )


def tag(text: str, x: int, y: int, width: int) -> str:
    return f"""
    <rect x="{x}" y="{y}" width="{width}" height="46" rx="23" fill="#dff7fb"/>
    <text x="{x + width / 2}" y="{y + 30}" text-anchor="middle" class="tag">{text}</text>
    """


def visual_config_quote() -> str:
    return """
    <rect x="906" y="166" width="548" height="560" rx="32" fill="#ffffff" opacity="0.97"/>
    <text x="950" y="220" class="visualTitle">报价配置工作台</text>
    <rect x="950" y="255" width="202" height="82" rx="18" fill="#e9f8fb"/>
    <text x="976" y="290" class="miniTitle">客户与场景</text>
    <text x="976" y="318" class="mini">客户 · 币种 · 条款</text>
    <rect x="1174" y="255" width="236" height="82" rx="18" fill="#edf1ff"/>
    <text x="1200" y="290" class="miniTitle">产品配置</text>
    <text x="1200" y="318" class="mini">料号 · 属性 · 数量</text>
    <path d="M1050 350 L1050 383 L1290 383 L1290 350" fill="none" stroke="#56cfe1" stroke-width="4"/>
    <path d="M1170 383 L1170 421" fill="none" stroke="#56cfe1" stroke-width="4" marker-end="url(#arrow)"/>
    <rect x="950" y="425" width="460" height="118" rx="22" fill="#0b3049"/>
    <text x="980" y="464" class="whiteMini">配置自动装配</text>
    <rect x="980" y="485" width="94" height="34" rx="17" fill="#155b75"/><text x="1027" y="508" text-anchor="middle" class="whiteTiny">BOM</text>
    <rect x="1084" y="485" width="94" height="34" rx="17" fill="#155b75"/><text x="1131" y="508" text-anchor="middle" class="whiteTiny">工艺</text>
    <rect x="1188" y="485" width="94" height="34" rx="17" fill="#155b75"/><text x="1235" y="508" text-anchor="middle" class="whiteTiny">公式</text>
    <rect x="1292" y="485" width="88" height="34" rx="17" fill="#155b75"/><text x="1336" y="508" text-anchor="middle" class="whiteTiny">策略</text>
    <rect x="950" y="570" width="290" height="112" rx="20" fill="#f1f6f9"/>
    <text x="980" y="605" class="mini">报价总额</text>
    <text x="980" y="653" class="metric">128,640</text>
    <text x="1172" y="652" class="mini">CNY</text>
    <circle cx="1336" cy="626" r="58" fill="none" stroke="#d9e8ef" stroke-width="16"/>
    <path d="M1336 568 A58 58 0 1 1 1285 654" fill="none" stroke="#0bbbd6" stroke-width="16" stroke-linecap="round"/>
    <text x="1336" y="620" text-anchor="middle" class="miniTitle">利润</text>
    <text x="1336" y="650" text-anchor="middle" class="mini">可控可溯</text>
    """


def visual_template() -> str:
    rows = []
    for index, y in enumerate((262, 382, 502)):
        fill = "#e9f8fb" if index != 1 else "#edf1ff"
        rows.append(f"""
        <rect x="930" y="{y}" width="476" height="92" rx="18" fill="{fill}"/>
        <rect x="956" y="{y + 20}" width="94" height="16" rx="8" fill="#0bbbd6" opacity="0.75"/>
        <rect x="1068" y="{y + 20}" width="132" height="16" rx="8" fill="#6c8be8" opacity="0.55"/>
        <rect x="1218" y="{y + 20}" width="74" height="16" rx="8" fill="#0bbbd6" opacity="0.45"/>
        <rect x="1310" y="{y + 20}" width="70" height="16" rx="8" fill="#6c8be8" opacity="0.45"/>
        <rect x="956" y="{y + 54}" width="312" height="12" rx="6" fill="#a9c2ce" opacity="0.55"/>
        <circle cx="1350" cy="{y + 58}" r="16" fill="#ffffff" stroke="#0bbbd6" stroke-width="4"/>
        <path d="M1342 {y + 58} l6 6 l12 -14" fill="none" stroke="#0bbbd6" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
        """)
    return f"""
    <rect x="890" y="150" width="570" height="590" rx="34" fill="#ffffff" opacity="0.97"/>
    <text x="930" y="208" class="visualTitle">统一模板字段</text>
    <text x="1406" y="208" text-anchor="end" class="mini">v3.2 · 已发布</text>
    {''.join(rows)}
    <line x1="912" y1="308" x2="912" y2="548" stroke="#0bbbd6" stroke-width="4"/>
    <circle cx="912" cy="308" r="10" fill="#0bbbd6"/>
    <circle cx="912" cy="428" r="10" fill="#4776e6"/>
    <circle cx="912" cy="548" r="10" fill="#0bbbd6"/>
    <rect x="930" y="640" width="476" height="60" rx="18" fill="#0b3049"/>
    <text x="1168" y="679" text-anchor="middle" class="whiteMini">字段统一 · 版本固定 · 每行可追溯</text>
    """


def visual_bom_process() -> str:
    return """
    <rect x="870" y="144" width="610" height="604" rx="34" fill="#ffffff" opacity="0.97"/>
    <text x="914" y="204" class="visualTitle">BOM 结构</text>
    <text x="1210" y="204" class="visualTitle">工艺路线</text>
    <line x1="1010" y1="264" x2="1010" y2="552" stroke="#b4d0da" stroke-width="4"/>
    <line x1="1010" y1="320" x2="930" y2="320" stroke="#b4d0da" stroke-width="4"/>
    <line x1="1010" y1="420" x2="930" y2="420" stroke="#b4d0da" stroke-width="4"/>
    <line x1="1010" y1="520" x2="930" y2="520" stroke="#b4d0da" stroke-width="4"/>
    <circle cx="1010" cy="264" r="34" fill="#0bbbd6"/><text x="1010" y="273" text-anchor="middle" class="whiteMini">成品</text>
    <rect x="890" y="291" width="104" height="58" rx="18" fill="#e9f8fb"/><text x="942" y="328" text-anchor="middle" class="miniTitle">料件</text>
    <rect x="890" y="391" width="104" height="58" rx="18" fill="#edf1ff"/><text x="942" y="428" text-anchor="middle" class="miniTitle">材料</text>
    <rect x="890" y="491" width="104" height="58" rx="18" fill="#e9f8fb"/><text x="942" y="528" text-anchor="middle" class="miniTitle">元素</text>
    <path d="M1080 320 L1400 320" stroke="#56cfe1" stroke-width="5" marker-end="url(#arrow)"/>
    <circle cx="1130" cy="320" r="38" fill="#e9f8fb" stroke="#0bbbd6" stroke-width="4"/><text x="1130" y="328" text-anchor="middle" class="miniTitle">来料</text>
    <circle cx="1240" cy="320" r="38" fill="#edf1ff" stroke="#4776e6" stroke-width="4"/><text x="1240" y="328" text-anchor="middle" class="miniTitle">加工</text>
    <circle cx="1350" cy="320" r="38" fill="#e9f8fb" stroke="#0bbbd6" stroke-width="4"/><text x="1350" y="328" text-anchor="middle" class="miniTitle">组装</text>
    <path d="M1130 400 L1400 400" stroke="#6f8fe8" stroke-width="5" marker-end="url(#arrowBlue)"/>
    <circle cx="1190" cy="400" r="38" fill="#edf1ff" stroke="#4776e6" stroke-width="4"/><text x="1190" y="408" text-anchor="middle" class="miniTitle">电镀</text>
    <circle cx="1330" cy="400" r="38" fill="#e9f8fb" stroke="#0bbbd6" stroke-width="4"/><text x="1330" y="408" text-anchor="middle" class="miniTitle">检验</text>
    <path d="M1234 496 l35 35 l74 -86" fill="none" stroke="#0bbbd6" stroke-width="18" stroke-linecap="round" stroke-linejoin="round"/>
    <text x="1288" y="584" text-anchor="middle" class="visualTitle">系统强校验</text>
    <text x="1288" y="624" text-anchor="middle" class="mini">缺项提醒 · 关系校验 · 结果汇总</text>
    <rect x="914" y="666" width="522" height="46" rx="23" fill="#0b3049"/>
    <text x="1175" y="696" text-anchor="middle" class="whiteMini">结构与工艺同源，减少遗漏与错报</text>
    """


def visual_profit() -> str:
    bars = [
        ("材料", 92, "#0bbbd6"),
        ("加工", 58, "#2aa8d5"),
        ("费用", 40, "#4776e6"),
        ("税费", 34, "#7a8ee8"),
        ("利润", 76, "#19b78a"),
    ]
    x = 938
    parts = []
    for label, width, color in bars:
        parts.append(f'<rect x="{x}" y="580" width="{width}" height="58" fill="{color}"/><text x="{x + width/2}" y="615" text-anchor="middle" class="whiteTiny">{label}</text>')
        x += width
    return f"""
    <rect x="864" y="146" width="618" height="598" rx="34" fill="#ffffff" opacity="0.97"/>
    <text x="914" y="208" class="visualTitle">报价提交前</text>
    <rect x="914" y="244" width="518" height="102" rx="22" fill="#f1f6f9"/>
    <text x="946" y="284" class="mini">建议报价</text>
    <text x="946" y="329" class="metric">¥ 128,640</text>
    <rect x="1210" y="270" width="180" height="52" rx="26" fill="#def7ed"/>
    <text x="1300" y="304" text-anchor="middle" class="greenTitle">利润空间健康</text>
    <line x1="914" y1="400" x2="1432" y2="400" stroke="#dce9ee" stroke-width="3"/>
    <text x="914" y="436" class="miniTitle">价格形成过程</text>
    <rect x="938" y="480" width="405" height="30" rx="15" fill="#e7eef2"/>
    <rect x="938" y="480" width="312" height="30" rx="15" fill="#0bbbd6"/>
    <text x="1365" y="503" class="mini">毛利可见</text>
    {''.join(parts)}
    <text x="938" y="674" class="mini">料 + 工 + 费 + 汇率 + 税费 + 利润</text>
    <path d="M1325 452 C1395 470 1415 535 1378 590" fill="none" stroke="#4776e6" stroke-width="5" marker-end="url(#arrowBlue)"/>
    """


def visual_trace() -> str:
    return """
    <rect x="858" y="144" width="630" height="604" rx="34" fill="#ffffff" opacity="0.97"/>
    <text x="908" y="204" class="visualTitle">价格变化影响链</text>
    <rect x="908" y="244" width="152" height="80" rx="20" fill="#e9f8fb"/><text x="984" y="278" text-anchor="middle" class="miniTitle">材料价格</text><text x="984" y="307" text-anchor="middle" class="delta">+3.2%</text>
    <rect x="1080" y="244" width="152" height="80" rx="20" fill="#edf1ff"/><text x="1156" y="278" text-anchor="middle" class="miniTitle">汇率</text><text x="1156" y="307" text-anchor="middle" class="delta">-1.1%</text>
    <rect x="1252" y="244" width="152" height="80" rx="20" fill="#e9f8fb"/><text x="1328" y="278" text-anchor="middle" class="miniTitle">工费</text><text x="1328" y="307" text-anchor="middle" class="delta">+0.8%</text>
    <path d="M984 344 L984 380 L1328 380 L1328 344" fill="none" stroke="#56cfe1" stroke-width="4"/>
    <path d="M1156 380 L1156 420" fill="none" stroke="#56cfe1" stroke-width="4" marker-end="url(#arrow)"/>
    <rect x="944" y="426" width="424" height="92" rx="22" fill="#0b3049"/>
    <text x="1156" y="464" text-anchor="middle" class="whiteMini">自动识别影响范围</text>
    <text x="1156" y="494" text-anchor="middle" class="whiteTiny">客户 · 产品 · 报价单 · 利润变化</text>
    <line x1="936" y1="580" x2="1388" y2="580" stroke="#b7ced8" stroke-width="4"/>
    <circle cx="958" cy="580" r="16" fill="#0bbbd6"/><text x="958" y="628" text-anchor="middle" class="mini">来源</text>
    <circle cx="1090" cy="580" r="16" fill="#2aa8d5"/><text x="1090" y="628" text-anchor="middle" class="mini">版本</text>
    <circle cx="1222" cy="580" r="16" fill="#4776e6"/><text x="1222" y="628" text-anchor="middle" class="mini">计算</text>
    <circle cx="1366" cy="580" r="16" fill="#19b78a"/><text x="1366" y="628" text-anchor="middle" class="mini">调价依据</text>
    <rect x="944" y="668" width="424" height="48" rx="24" fill="#e3f8f3"/>
    <text x="1156" y="700" text-anchor="middle" class="greenTitle">一键回溯 · 调价有据</text>
    """


def visual_speed() -> str:
    return """
    <rect x="860" y="142" width="624" height="608" rx="34" fill="#ffffff" opacity="0.97"/>
    <text x="910" y="204" class="visualTitle">报价时间重构</text>
    <circle cx="1050" cy="382" r="128" fill="#edf7fa" stroke="#0bbbd6" stroke-width="18"/>
    <line x1="1050" y1="382" x2="1050" y2="300" stroke="#0b3049" stroke-width="14" stroke-linecap="round"/>
    <line x1="1050" y1="382" x2="1120" y2="418" stroke="#4776e6" stroke-width="14" stroke-linecap="round"/>
    <circle cx="1050" cy="382" r="16" fill="#0b3049"/>
    <text x="1050" y="554" text-anchor="middle" class="mini">模板复用 · 自动填充</text>
    <path d="M1208 320 L1392 320" stroke="#b5ccd6" stroke-width="8" marker-end="url(#arrow)"/>
    <text x="1208" y="286" class="mini">过去</text>
    <text x="1208" y="374" class="oldMetric">小时级</text>
    <text x="1208" y="430" class="mini">现在</text>
    <text x="1208" y="494" class="newMetric">分钟级</text>
    <rect x="910" y="610" width="520" height="96" rx="22" fill="#0b3049"/>
    <rect x="940" y="634" width="132" height="46" rx="23" fill="#155b75"/><text x="1006" y="664" text-anchor="middle" class="whiteTiny">自动取数</text>
    <rect x="1084" y="634" width="132" height="46" rx="23" fill="#155b75"/><text x="1150" y="664" text-anchor="middle" class="whiteTiny">自动计算</text>
    <rect x="1228" y="634" width="172" height="46" rx="23" fill="#155b75"/><text x="1314" y="664" text-anchor="middle" class="whiteTiny">经验沉淀</text>
    """


CARDS = [
    {
        "file": "01-产品配置报价系统",
        "number": "01",
        "eyebrow": "CONFIGURE TO QUOTE",
        "title": "产品配置报价系统",
        "subtitle": "精准报价 · 配置即报价 · 每一单利润可控可溯",
        "body": ["把客户、产品、BOM 与定价策略", "组合为可计算的报价上下文，", "让配置结果直接形成报价结果。"],
        "tags": [("配置即报价", 150), ("利润可控", 136), ("全程可溯", 136)],
        "visual": visual_config_quote,
    },
    {
        "file": "02-标准化报价模板",
        "number": "02",
        "eyebrow": "STANDARDIZED TEMPLATE",
        "title": "标准化报价模板",
        "subtitle": "报价与核价字段统一，每一行成本都可追溯",
        "body": ["统一报价、核价与 Excel 视图字段，", "模板版本固定业务口径，", "审查从翻表格变成看数据链。"],
        "tags": [("字段统一", 124), ("版本固定", 124), ("逐行溯源", 136)],
        "visual": visual_template,
    },
    {
        "file": "03-BOM与工艺一体化",
        "number": "03",
        "eyebrow": "BOM AND PROCESS",
        "title": "BOM 与工艺一体化",
        "subtitle": "料件、材料、元素、组装和电镀进入同一模型",
        "body": ["BOM 结构与工艺路线协同计算，", "由系统执行关系与完整性校验，", "减少复杂报价中的遗漏和错报。"],
        "tags": [("统一模型", 124), ("系统强校验", 150), ("复杂报价", 136)],
        "visual": visual_bom_process,
    },
    {
        "file": "04-成交前利润估算",
        "number": "04",
        "eyebrow": "PROFIT BEFORE COMMIT",
        "title": "成交前利润估算",
        "subtitle": "料、工、费、汇率与毛利在提交前实时呈现",
        "body": ["汇总成本、费用、汇率与定价结果，", "让销售和审批者看清价格构成，", "报价利润从黑盒变成白盒。"],
        "tags": [("利润白盒", 136), ("提交前可见", 150), ("风险前置", 124)],
        "visual": visual_profit,
    },
    {
        "file": "05-完整数据链-调价不失利",
        "number": "05",
        "eyebrow": "TRACEABLE DATA CHAIN",
        "title": "完整数据链 · 调价不失利",
        "subtitle": "自动捕获材料、汇率和工费变化，回溯影响范围",
        "body": ["连接数据来源、版本、计算与报价，", "价格变化后快速定位受影响对象，", "让调价有据、利润持续可控。"],
        "tags": [("自动捕获", 136), ("一键回溯", 136), ("调价有据", 136)],
        "visual": visual_trace,
    },
    {
        "file": "06-报价提速-小时级到分钟级",
        "number": "06",
        "eyebrow": "FASTER QUOTING",
        "title": "报价提速：小时级到分钟级",
        "subtitle": "配置即报价，模板自动填充，经验沉淀进系统",
        "body": ["模板复用、自动取数和公式计算", "减少重复整理与跨表核对，", "降低新人上手门槛并加快出单。"],
        "tags": [("模板复用", 124), ("自动完成", 124), ("经验沉淀", 136)],
        "visual": visual_speed,
    },
]


def card_svg(card: dict) -> str:
    tag_x = 84
    tags = []
    for label, width in card["tags"]:
        tags.append(tag(label, tag_x, 640, width))
        tag_x += width + 18
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900" viewBox="0 0 1600 900">
  <defs>
    <linearGradient id="background" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#f3fbfd"/>
      <stop offset="1" stop-color="#eaf0ff"/>
    </linearGradient>
    <linearGradient id="number" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0bbbd6"/>
      <stop offset="1" stop-color="#4776e6"/>
    </linearGradient>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="150%">
      <feDropShadow dx="0" dy="18" stdDeviation="24" flood-color="#163d57" flood-opacity="0.15"/>
    </filter>
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10z" fill="#56cfe1"/></marker>
    <marker id="arrowBlue" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10z" fill="#4776e6"/></marker>
    <style>
      .eyebrow {{ font: 700 18px 'Noto Sans SC', sans-serif; letter-spacing: 4px; fill: #0b87a5; }}
      .number {{ font: 700 26px 'Noto Sans SC', sans-serif; fill: #fff; }}
      .title {{ font: 700 58px 'Noto Sans SC', sans-serif; fill: #092b43; }}
      .subtitle {{ font: 500 28px 'Noto Sans SC', sans-serif; fill: #315a70; }}
      .body {{ font: 400 25px 'Noto Sans SC', sans-serif; fill: #4c687a; }}
      .tag {{ font: 500 19px 'Noto Sans SC', sans-serif; fill: #087f9f; }}
      .visualTitle {{ font: 700 27px 'Noto Sans SC', sans-serif; fill: #0b3049; }}
      .miniTitle {{ font: 700 19px 'Noto Sans SC', sans-serif; fill: #173e55; }}
      .mini {{ font: 400 17px 'Noto Sans SC', sans-serif; fill: #557486; }}
      .whiteMini {{ font: 600 18px 'Noto Sans SC', sans-serif; fill: #fff; }}
      .whiteTiny {{ font: 500 16px 'Noto Sans SC', sans-serif; fill: #fff; }}
      .metric {{ font: 700 37px 'Noto Sans SC', sans-serif; fill: #092b43; }}
      .delta {{ font: 700 19px 'Noto Sans SC', sans-serif; fill: #e26363; }}
      .greenTitle {{ font: 700 18px 'Noto Sans SC', sans-serif; fill: #168667; }}
      .oldMetric {{ font: 700 31px 'Noto Sans SC', sans-serif; fill: #869da9; text-decoration: line-through; }}
      .newMetric {{ font: 700 43px 'Noto Sans SC', sans-serif; fill: #0b9cb8; }}
    </style>
  </defs>
  <rect width="1600" height="900" fill="url(#background)"/>
  <circle cx="1510" cy="70" r="280" fill="#0bbbd6" opacity="0.08"/>
  <circle cx="70" cy="880" r="330" fill="#4776e6" opacity="0.07"/>
  <rect x="48" y="48" width="1504" height="804" rx="42" fill="none" stroke="#ffffff" stroke-width="2" opacity="0.8"/>
  <circle cx="118" cy="104" r="34" fill="url(#number)"/>
  <text x="118" y="113" text-anchor="middle" class="number">{card['number']}</text>
  <text x="172" y="111" class="eyebrow">{card['eyebrow']}</text>
  <text x="84" y="235" class="title">{card['title']}</text>
  <text x="84" y="293" class="subtitle">{card['subtitle']}</text>
  <rect x="84" y="342" width="92" height="6" rx="3" fill="#0bbbd6"/>
  {line_text(card['body'], 84, 410)}
  {''.join(tags)}
  <text x="84" y="786" class="mini">CPQ SYSTEM · BUSINESS HIGHLIGHT</text>
  <g filter="url(#shadow)">{card['visual']()}</g>
</svg>
"""


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for card in CARDS:
        target = OUTPUT / f"{card['file']}.svg"
        target.write_text(card_svg(card), encoding="utf-8")
        print(target)


if __name__ == "__main__":
    main()
