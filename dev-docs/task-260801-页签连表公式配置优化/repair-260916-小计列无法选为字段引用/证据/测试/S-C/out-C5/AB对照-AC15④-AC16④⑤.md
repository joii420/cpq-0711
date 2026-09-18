| AC | 维度 | 本分支 | master | 结论 |
|---|---|---|---|---|
| AC-16④ | v1.0 提交 HTTP 状态/code | 400/400 | 400/400 | 一致 |
| AC-16④ | v1.0 提交报错文字（逐字） | "组件「物料」(COMP-2649) 导入失败：公式「零件材料成本」使用了父子取值（tree_ref/tree_attr），该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType=(未配置)。该组件的页签类型是在「取数配置器」里配的（树身份记在配置器信息里，不在 tabType 列），而这个导入包是旧格式（bundleVersion 1.0，不含配置器信息），导入端无从得知它是树页签。请在源库升级到含本次修复的版本后重新导出，再导入本包。" | "组件「物料」(COMP-2664) 导入失败：公式「零件材料成本」使用了父子取值（tree_ref/tree_attr），该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType=(未配置)。该组件的页签类型是在「取数配置器」里配的（树身份记在配置器信息里，不在 tabType 列），而这个导入包是旧格式（bundleVersion 1.0，不含配置器信息），导入端无从得知它是树页签。请在源库升级到含本次修复的版本后重新导出，再导入本包。" | ❌ 不一致 |
| AC-16④ | v1.0 提交报错文字（屏蔽自动生成的组件编号后逐字） | "组件「物料」(COMP-####) 导入失败：公式「零件材料成本」使用了父子取值（tree_ref/tree_attr），该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType=(未配置)。该组件的页签类型是在「取数配置器」里配的（树身份记在配置器信息里，不在 tabType 列），而这个导入包是旧格式（bundleVersion 1.0，不含配置器信息），导入端无从得知它是树页签。请在源库升级到含本次修复的版本后重新导出，再导入本包。" | "组件「物料」(COMP-####) 导入失败：公式「零件材料成本」使用了父子取值（tree_ref/tree_attr），该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType=(未配置)。该组件的页签类型是在「取数配置器」里配的（树身份记在配置器信息里，不在 tabType 列），而这个导入包是旧格式（bundleVersion 1.0，不含配置器信息），导入端无从得知它是树页签。请在源库升级到含本次修复的版本后重新导出，再导入本包。" | 一致 |
| AC-16④（参考） | v1.0 预览 版本/warnings/blockers | {"bundleVersion":"1.0","checksumValid":true,"canCommit":true,"blockers":[],"warnings":[]} | {"bundleVersion":"1.0","checksumValid":true,"canCommit":true,"blockers":[],"warnings":[]} | 一致 |
| AC-16④（参考） | v1.1 预览 版本/warnings/blockers | {"bundleVersion":"1.1","checksumValid":true,"canCommit":true,"blockers":[],"warnings":[]} | {"bundleVersion":"1.1","checksumValid":true,"canCommit":true,"blockers":[],"warnings":[]} | 一致 |
| AC-16⑤ | 界面预览 v1.0 旧格式提示 | 有(版本 1.0) 导入包是旧格式(bundleVersion 1.0)，不含取数配置器信息 / bundle 版本	1.0旧格式	checksum	校验通过 | 有(版本 1.0) 导入包是旧格式(bundleVersion 1.0)，不含取数配置器信息 / bundle 版本	1.0旧格式	checksum	校验通过 | 一致 |
| AC-16⑤ | 界面预览 v1.1 旧格式提示 | 无  | 无  | 一致 |
| AC-15④ | 后端重算 col_1（原始值逐字） | "0.003407173" | "0.003407173" | 一致 |
| AC-15④ | 后端重算 col_2（原始值逐字） | "0" | "0" | 一致 |
| AC-15④ | 后端重算 col_3（原始值逐字） | "39.547708538" | "39.547708538" | 一致 |
