# AC-6 / AC-7 · md5 清单（S-全局，验收库 `cpq_db_260916`）

> 由 `cpq-frontend/e2e/repair260916-global-2-write.spec.ts` 自动生成；每个快照一节，按时间追加。

## AC-6 调用前

采样时刻（UTC）：2026-09-17T03:02:37.591Z

- component_sql_view 全表 md5：`24c66e77e1b1b8a48240a910dee02aa4`
- template_component_snapshot 全表 md5：`31e435f4fc0e49cd4458b8feb341bdfd`
- operation_log 行数：170（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：0）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | ba3c87bc73c3596d939e6ff1319fae65 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 01:39:24.495632 |
| builder_4602c64a0c38 | b56b315ff3292de89c7f61359f789380 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 01:39:24.49632 |
| builder_46f244df7ede | d92b26ab752fe546cab6a7d007cbc02d | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 01:39:24.497968 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`75e22e0a57de835476ec9ba9d050f5c7`

## AC-6 调用后

采样时刻（UTC）：2026-09-17T03:02:38.620Z

- component_sql_view 全表 md5：`24c66e77e1b1b8a48240a910dee02aa4`
- template_component_snapshot 全表 md5：`31e435f4fc0e49cd4458b8feb341bdfd`
- operation_log 行数：170（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：0）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | ba3c87bc73c3596d939e6ff1319fae65 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 01:39:24.495632 |
| builder_4602c64a0c38 | b56b315ff3292de89c7f61359f789380 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 01:39:24.49632 |
| builder_46f244df7ede | d92b26ab752fe546cab6a7d007cbc02d | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 01:39:24.497968 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`75e22e0a57de835476ec9ba9d050f5c7`

## AC-7 执行后

采样时刻（UTC）：2026-09-17T03:02:45.660Z

- component_sql_view 全表 md5：`31fa1ad3abaf6f7e0dece4b182590daa`
- template_component_snapshot 全表 md5：`31e435f4fc0e49cd4458b8feb341bdfd`
- operation_log 行数：173（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：3）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | 9a4a3949d427493fb00943231cab45c6 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 20:02:44.86985 |
| builder_4602c64a0c38 | 0aee4c4ed52ad8b40187603d83e9c7e6 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 20:02:44.940983 |
| builder_46f244df7ede | 829a17e8d3fca2e128e2153c04a686ad | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 20:02:44.80731 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`75e22e0a57de835476ec9ba9d050f5c7`

## 续跑现查（AC-7 执行后、T3.6 之前）

采样时刻（UTC）：2026-09-17T03:34:56.384Z　只读现查；与「AC-7 执行后」比对即可得出执行后全量预览及此后是否写过视图

- component_sql_view 全表 md5：`31fa1ad3abaf6f7e0dece4b182590daa`
- template_component_snapshot 全表 md5：`31e435f4fc0e49cd4458b8feb341bdfd`
- operation_log 行数：173（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：3）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | 9a4a3949d427493fb00943231cab45c6 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 20:02:44.86985 |
| builder_4602c64a0c38 | 0aee4c4ed52ad8b40187603d83e9c7e6 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 20:02:44.940983 |
| builder_46f244df7ede | 829a17e8d3fca2e128e2153c04a686ad | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 20:02:44.80731 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`75e22e0a57de835476ec9ba9d050f5c7`

## 续跑现查（AC-7 执行后、T3.6 之前）

采样时刻（UTC）：2026-09-17T03:36:22.613Z　只读现查；与「AC-7 执行后」比对即可得出执行后全量预览及此后是否写过视图

- component_sql_view 全表 md5：`31fa1ad3abaf6f7e0dece4b182590daa`
- template_component_snapshot 全表 md5：`31e435f4fc0e49cd4458b8feb341bdfd`
- operation_log 行数：173（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：3）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | 9a4a3949d427493fb00943231cab45c6 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 20:02:44.86985 |
| builder_4602c64a0c38 | 0aee4c4ed52ad8b40187603d83e9c7e6 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 20:02:44.940983 |
| builder_46f244df7ede | 829a17e8d3fca2e128e2153c04a686ad | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 20:02:44.80731 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`75e22e0a57de835476ec9ba9d050f5c7`


## E-2 阳性对照（来源:run6实跑：AC-6 调用后 → AC-7 执行后）

```json
{
  "source": "来源:run6实跑",
  "before": {
    "at": "2026-09-17T03:02:38.620Z",
    "viewsTableMd5": "24c66e77e1b1b8a48240a910dee02aa4",
    "opLogCount": 170
  },
  "after": {
    "at": "2026-09-17T03:02:45.660Z",
    "viewsTableMd5": "31fa1ad3abaf6f7e0dece4b182590daa",
    "opLogCount": 173
  },
  "viewsTableMd5Changed": true,
  "threeSqlChanged": true,
  "opLogDelta": 3,
  "recompileAuditDelta": 3
}
```
## 续跑现查（AC-7 执行后、T3.6 之前）

采样时刻（UTC）：2026-09-17T03:41:35.011Z　只读现查；与「AC-7 执行后」比对即可得出执行后全量预览及此后是否写过视图

- component_sql_view 全表 md5：`31fa1ad3abaf6f7e0dece4b182590daa`
- template_component_snapshot 全表 md5：`1a36045bc6a06fd6f280df81272c1f42`
- operation_log 行数：173（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：3）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | 9a4a3949d427493fb00943231cab45c6 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 20:02:44.86985 |
| builder_4602c64a0c38 | 0aee4c4ed52ad8b40187603d83e9c7e6 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 20:02:44.940983 |
| builder_46f244df7ede | 829a17e8d3fca2e128e2153c04a686ad | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 20:02:44.80731 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |
| v1.7 (PUBLISHED) | fbb0545d-73f3-497e-8649-af968f86c42b | aca26721d47064e7aaaa0bfe18660cb5 | 5fed9f8a8c60c8ce5db1463141239d51 | 99914b932bd37a50b983c5e7c90ae93b | 2b954ead62d2209edf05d739e3991d7c | 2026-09-17 03:37:15.67503+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`1fd35555aeda3e6a45179d262b6e21ec`


## E-2 阳性对照（来源:run6实跑：AC-6 调用后 → AC-7 执行后）

```json
{
  "source": "来源:run6实跑",
  "before": {
    "at": "2026-09-17T03:02:38.620Z",
    "viewsTableMd5": "24c66e77e1b1b8a48240a910dee02aa4",
    "opLogCount": 170
  },
  "after": {
    "at": "2026-09-17T03:02:45.660Z",
    "viewsTableMd5": "31fa1ad3abaf6f7e0dece4b182590daa",
    "opLogCount": 173
  },
  "viewsTableMd5Changed": true,
  "threeSqlChanged": true,
  "opLogDelta": 3,
  "recompileAuditDelta": 3
}
```
## 续跑现查（AC-7 执行后、T3.6 之前）

采样时刻（UTC）：2026-09-17T03:45:44.382Z　只读现查；与「AC-7 执行后」比对即可得出执行后全量预览及此后是否写过视图

- component_sql_view 全表 md5：`31fa1ad3abaf6f7e0dece4b182590daa`
- template_component_snapshot 全表 md5：`1a36045bc6a06fd6f280df81272c1f42`
- operation_log 行数：173（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：3）

| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |
|---|---|---|---|---|---|
| builder_c35c2bd590fe | 9a4a3949d427493fb00943231cab45c6 | 73c06f18535e289c01bc4ed03cb25716 | 8b85a9852d601f66c0ac08ea72447df5 | 1 | 2026-09-16 20:02:44.86985 |
| builder_4602c64a0c38 | 0aee4c4ed52ad8b40187603d83e9c7e6 | 0ffe001437f30255a80e523238b26b02 | b322958e8b6a528d5a3e99a86442b3a3 | 1 | 2026-09-16 20:02:44.940983 |
| builder_46f244df7ede | 829a17e8d3fca2e128e2153c04a686ad | 16a5d44f5872ab6bac14d59d3e928e44 | c4bdbfef5c1bdbc2ef940a1c7c0d834e | 1 | 2026-09-16 20:02:44.80731 |

| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |
|---|---|---|---|---|---|
| COMP-0004 | 9ff3131e0a1067234983bccd905cc26e | d751713988987e9331980363e24189ce | b30443b7db0fa63c660e1fc3c56a1a6b | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495332+00 |
| COMP-0005 | 523f0d66420f640781d43fbcb97376fc | d751713988987e9331980363e24189ce | caf4d61c4ed6173eaa7ee7b4abe744e7 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.495804+00 |
| COMP-0008 | 418b0f97c8c4b88c4a71ffeaaa6f9141 | d751713988987e9331980363e24189ce | 1bb37ce3f37f3229cb9989d5d949f198 | 料号 / 材料名 / 项次 | 2026-09-16 08:39:24.497853+00 |

| 施耐德5.4模板 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |
|---|---|---|---|---|---|---|
| v1.0 (PUBLISHED) | 82d31ed8-199e-4c9c-b6c3-2fcfc07defdc | 73549a043a0ddd9967368dba8f75a24c | a4293491b3fc46e9d46aa2486bdd667e | 99914b932bd37a50b983c5e7c90ae93b | 3232e3ad171395d1fc6afe6698f126d2 | 2026-09-17 01:42:54.171023+00 |
| v1.1 (PUBLISHED) | d79a4784-e52b-4dc7-a6ca-28d025aadc27 | 73549a043a0ddd9967368dba8f75a24c | 1fe5e0992b6c49b3e6478d94df6ba863 | 99914b932bd37a50b983c5e7c90ae93b | c2c6fa455d501b2f7dc21a6b417dd531 | 2026-09-17 01:42:54.171023+00 |
| v1.2 (PUBLISHED) | e381c6a6-63e0-4ef4-b633-58dc8093441c | 73549a043a0ddd9967368dba8f75a24c | 218cf3b949bd85747752a338c2dc4339 | 99914b932bd37a50b983c5e7c90ae93b | 4ee8dbeaecd75a302a9268d2a1a1ee06 | 2026-09-17 01:42:54.171023+00 |
| v1.3 (PUBLISHED) | 432f060e-5ad3-4c17-9788-0d4709b238e9 | 73549a043a0ddd9967368dba8f75a24c | 667846d896587610cafe2eaf63ba1713 | 99914b932bd37a50b983c5e7c90ae93b | d5779f03aace89603552e840f3fee253 | 2026-09-17 01:42:54.171023+00 |
| v1.4 (PUBLISHED) | 75baa71e-0c48-46bb-ba6f-5631b8bdbec6 | 73549a043a0ddd9967368dba8f75a24c | be8febacae54b8b3737d09b6e29d5dd3 | 99914b932bd37a50b983c5e7c90ae93b | 8a94f29ec0659479360dacb017536c2a | 2026-09-17 01:42:54.171023+00 |
| v1.5 (PUBLISHED) | 6755f31c-4756-4ffb-a70c-211356f9be07 | 73549a043a0ddd9967368dba8f75a24c | 5355474e5388f881df865fa0b2ed8b9d | 99914b932bd37a50b983c5e7c90ae93b | 1419c9661e658b68775e6225fac08d81 | 2026-09-17 01:42:54.171023+00 |
| v1.6 (PUBLISHED) | 9516751e-410a-4b02-8a59-26484c4129aa | 299249544f86cd4e0f9f02c2e60273c6 | 292fbec30468050a8188d1cd757ac86e | 99914b932bd37a50b983c5e7c90ae93b | c55873e0ba09f5eb7644de05d538ffe8 | 2026-09-17 01:43:30.88743+00 |
| v1.7 (PUBLISHED) | fbb0545d-73f3-497e-8649-af968f86c42b | aca26721d47064e7aaaa0bfe18660cb5 | 5fed9f8a8c60c8ce5db1463141239d51 | 99914b932bd37a50b983c5e7c90ae93b | 2b954ead62d2209edf05d739e3991d7c | 2026-09-17 03:37:15.67503+00 |

- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：`1fd35555aeda3e6a45179d262b6e21ec`


## E-2 阳性对照（来源:run6实跑：AC-6 调用后 → AC-7 执行后）

```json
{
  "source": "来源:run6实跑",
  "before": {
    "at": "2026-09-17T03:02:38.620Z",
    "viewsTableMd5": "24c66e77e1b1b8a48240a910dee02aa4",
    "opLogCount": 170
  },
  "after": {
    "at": "2026-09-17T03:02:45.660Z",
    "viewsTableMd5": "31fa1ad3abaf6f7e0dece4b182590daa",
    "opLogCount": 173
  },
  "viewsTableMd5Changed": true,
  "threeSqlChanged": true,
  "opLogDelta": 3,
  "recompileAuditDelta": 3
}
```
