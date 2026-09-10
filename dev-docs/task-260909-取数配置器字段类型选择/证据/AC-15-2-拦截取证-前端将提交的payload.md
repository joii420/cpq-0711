# AC-15② 安全取证（拦截 PUT /builder 并 abort，未写库）

- 捕获 PUT 次数 = 1

## 前端「什么都不改直接保存」将提交的 fieldType

- 生产料号=BASIC_DATA
- 项次=BASIC_DATA
- 料号=BASIC_DATA
- 材料名=BASIC_DATA
- 工序编号=BASIC_DATA
- 使用特性=BASIC_DATA
- 组成用量=BASIC_DATA
- 组成用量单位=BASIC_DATA
- 底数=BASIC_DATA
- 底数单位=BASIC_DATA
- 材料损耗率（%）=BASIC_DATA
- 材料固定损耗量=BASIC_DATA

## 库里真实值（对照）

- 生产料号=INPUT_TEXT
- 项次=INPUT_NUMBER
- 料号=INPUT_TEXT
- 材料名=INPUT_TEXT
- 工序编号=INPUT_TEXT
- 使用特性=INPUT_TEXT
- 组成用量=INPUT_NUMBER
- 组成用量单位=INPUT_TEXT
- 底数=INPUT_NUMBER
- 底数单位=INPUT_TEXT
- 材料损耗率（%）=INPUT_NUMBER
- 材料固定损耗量=INPUT_NUMBER
