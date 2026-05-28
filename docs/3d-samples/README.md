# 3D 模型样例与 CSV 模板

> 🧪 实验性 · 配套 `docs/3D产品选配方案.md` §六 UG NX 工作流
>
> 本目录提供 Babylon 3D 集成所需的样例资源，方便 POC / 验收阶段快速启动。

---

## 一、文件清单

| 文件 | 用途 | 状态 |
|---|---|---|
| `mesh-mapping-template.csv` | mesh → 业务实体映射 CSV 模板（8 行示例） | ✅ 已提供 |
| `models/composite-demo.glb` | 组合产品演示模型（POC 占位） | 🚧 待补（用代码生成或建模师产出） |
| `models/simple-box.glb` | 单 box 简单产品演示 | 🚧 待补 |
| `models/sn-multi.glb` | 多 mesh 性能测试模型 | 🚧 待补 |

> **注**: 当前 POC 阶段，所有 v0.4 原型 HTML 用代码生成几何体演示，**不依赖 glb 文件**。真实 glb 文件待阶段 1 POC 启动时由建模师补充。

---

## 二、CSV 模板字段说明（v0.2 特征中转版）

### `mesh-mapping-template.csv`

> **重大调整 (2026-05-25)**：mesh 不直接关联业务实体，而是通过 `featureCode` 关联到**全局特征**。业务实体引用 (PROCESS/MATERIAL/PART) 由特征独立维护（在特征字典 `/master/features` 页面配置）。

| 列名 | 必填 | 类型 | 取值范围 | 说明 |
|---|---|---|---|---|
| `partNo` | ✅ | VARCHAR(64) | 必须在 `mat_part` 表存在 | 关联料号 |
| `meshId` | ✅ | VARCHAR(128) | 必须在 glb 文件解析的 mesh 列表中 | 模型内 mesh 唯一标识 |
| `featureCode` | 条件必填 | VARCHAR(64) | 必须在 `mat_feature` 表存在；装饰类留空 | 关联特征代码（如 FEAT-WELD-LASER-A2）|
| `meshLabel` | ❌ | VARCHAR(128) | 任意文本 | 给管理员看的可读标签（不影响渲染）|
| `onClickAction` | ✅ | ENUM | `SHOW_FEATURE_DETAIL` / `HIGHLIGHT` / `NONE` | 点击行为；默认 `SHOW_FEATURE_DETAIL`；装饰类用 `NONE` |
| `sortOrder` | ❌ | INT | 数字，默认 0 | mesh 在 mapping 列表中的展示顺序 |

**字段简化对比**（v0.1 → v0.2）：
- ❌ 删除 `meshType` 列（feature_type 由 mat_feature 决定）
- ❌ 删除 `referenceCode` 列（业务实体引用归 mat_feature_reference 维护）
- ❌ 删除 `targetTab` 列（按 feature_reference.reference_type 自动决定）
- ✅ 新增 `featureCode` 列（mesh 唯一关联点）
- ✅ 简化 `onClickAction` 枚举（3 个值）

### 特征字典（mat_feature）单独维护

mesh-mapping CSV 只关心 mesh→feature 的绑定。特征本身的属性 (feature_type / metadata) 和业务实体引用 (mat_feature_reference) 通过单独 CSV / 管理端配置：

```csv
# mat_feature-template.csv (特征主数据)
code,name,featureType,categoryCode,metadata,status
FEAT-WELD-LASER-A2,激光焊缝 A2,WELD,CAT-WELD-LASER,"{""length"":""25mm"",""form"":""直焊缝""}",ACTIVE
FEAT-ASSEMBLY-AGCU85,Ag85Cu15 装配子件,INTERFACE,CAT-ASSEMBLY,"{""recipe"":""Ag85%-Cu15%""}",ACTIVE
```

```csv
# mat_feature_reference-template.csv (特征→业务实体引用)
featureCode,referenceType,referenceCode,notes,sortOrder
FEAT-WELD-LASER-A2,PROCESS,MRO-AS-0003,激光焊接工艺,1
FEAT-WELD-LASER-A2,MATERIAL,MAT-WELDWIRE-AGCU,银铜焊丝,2
FEAT-ASSEMBLY-AGCU85,MATERIAL,AGCU-85-15,银铜合金 85/15 配方,1
FEAT-ASSEMBLY-AGCU85,PART,CFG-AgCu-000008,关联子件料号,2
```

### feature_type 预定义枚举（封闭）

| feature_type | 含义 | 典型业务关联 |
|---|---|---|
| `THREAD` | 螺纹（孔/柱）| PROCESS (钻孔/攻丝) + MATERIAL (螺纹油) |
| `WELD` | 焊缝/焊接区 | PROCESS (焊接) + MATERIAL (焊丝/焊药) |
| `COATING` | 镀层/涂层 | MATERIAL (镀层配方) + PROCESS (镀层工艺) |
| `INTERFACE` | 装配接口（螺栓孔/卡扣）| PART (子件) + PROCESS (装配) |
| `SLOT` | 槽（键槽/导轨槽）| PROCESS (铣削) |
| `HOLE` | 通孔/盲孔（非螺纹）| PROCESS (钻孔) |
| `SURFACE` | 表面区（抛光/喷砂）| PROCESS (表面处理) + MATERIAL (抛光剂) |
| `GENERAL` | 通用其他 | 任意 |

### `referenceType` 对应业务表（特征→业务实体）

| referenceType | 关联表 | 关联字段 | 举例 |
|---|---|---|---|
| `PROCESS` | `mat_process` | `process_code` | `MRO-AS-0003` |
| `MATERIAL` | `material_recipe` | `code` | `AGCU-85-15` |
| `RECIPE` | `material_recipe` | `code`（特定配方）| `MAT-WELDWIRE-AGCU` |
| `PART` | `mat_part` | `part_no` (COMPOSITE 子件) | `CFG-AgCu-000008` |

---

## 三、上传 CSV 流程

### 方式 A：管理端 UI 上传

1. 访问 `/admin/parts/{partNo}/3d`
2. 进入 mesh 映射配置步骤（向导 Step 3）
3. 点击 **"📥 从 CSV 导入映射"**
4. 选择 csv 文件 → 自动解析 + 校验
5. 校验通过 → 表格自动填充 → 用户复核 → 保存

### 方式 B：批量 API 导入

```bash
curl -X POST http://localhost:8081/api/cpq/admin/parts/3d/batch-import \
  -H "Authorization: Bearer <admin-jwt>" \
  -F "file=@mesh-mapping-template.csv"
```

响应：
```json
{
  "success": 8,
  "failed": 0,
  "details": [
    { "row": 2, "partNo": "CFG-COMBO-000018", "status": "OK" },
    ...
  ]
}
```

### 方式 C：CLI 工具

```bash
cpq-3d-import \
  --csv ./mesh-mapping-template.csv \
  --models-dir ./models \
  --api http://cpq.local:8081 \
  --token $ADMIN_JWT
```

---

## 四、CSV 校验规则（后端会执行）

| 校验项 | 规则 | 失败行为 |
|---|---|---|
| **列名校验** | 必须含 8 列且名称正确 | 整个 CSV 拒绝 400 |
| **partNo 存在性** | 在 `mat_part` 表存在 | 该行跳过 + 记录到 `failed` |
| **meshId 存在性** | 在该 partNo 的 glb 文件 mesh 列表中 | 该行跳过 + 记录到 `failed` |
| **meshType 合法性** | 必须是 5 个 ENUM 值之一 | 该行跳过 |
| **referenceCode 存在性** | 按 meshType 查对应表 | 该行跳过 + 记录详细错误 |
| **唯一性** | 同 (partNo, meshId) 不可重复 | 该行跳过 + 警告 |
| **DECORATIVE 留空** | DECORATIVE 行 referenceCode / targetTab 必须留空 | 警告但允许 |

---

## 五、CAD 软件如何导出符合规范的 glb

详见 `docs/CAD导出GLB操作手册.md`（同目录上级），包含：
- SolidWorks 导出步骤
- CATIA 导出（中转 fbx → Blender → glb）
- Pro-E / Creo 导出
- Blender 优化（Draco 压缩 / 减面）
- Mesh 命名规范在 CAD 内的设置方法

---

## 六、Mesh 命名规范回顾（建模时强制约束）

建模时 mesh 必须按以下前缀命名，否则后端自动归为 DECORATIVE：

| 前缀 | 自动归类 |
|---|---|
| `mesh_child_*` | BOM_ITEM |
| `mesh_proc_*` / `mesh_weld_*` | PROCESS_ZONE |
| `mesh_mat_*` | MATERIAL_AREA |
| `mesh_composite_*` | COMPOSITE_CHILD |
| `mesh_base_*` / `mesh_deco_*` | DECORATIVE |

详见 `docs/3D产品选配方案.md §六 UG NX 工作流`。

---

## 七、版本管理

CSV 导入会替换该 partNo 当前激活版本的 mesh 映射。如需保留旧映射：

1. 先上传新 glb 文件创建新版本 v(N+1)
2. 用 CSV 导入新版本 v(N+1) 的映射
3. 切换激活版本（旧 vN 自动归档为非 current）

---

## 八、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-05-24 | v0.1 | 初版：CSV 模板 8 行示例 + 字段说明 + 上传方式 |
