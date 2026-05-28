# CAD 导出 GLB 操作手册

> 🧪 实验性 · 配套 `docs/3D产品选配方案.md` §六 UG NX 工作流
>
> **目标读者**：负责为 CPQ 系统上传 3D 模型的工程师 / 设计师 / 管理员

---

## 一、总览：从 CAD 到 CPQ 的标准流程

```
┌─────────────┐    ┌──────────────┐    ┌──────────────┐    ┌─────────────┐
│ CAD 软件    │    │ 中间格式      │    │ Blender 优化  │    │ CPQ 上传    │
│ SolidWorks  │ ─> │ .step / .iges│ ─> │ 减面 + 重命名 │ ─> │ .glb 上传   │
│ CATIA       │    │ .stl / .fbx  │    │ Draco 压缩   │    │ 配置 mesh   │
│ Pro-E/Creo  │    │              │    │              │    │ 映射激活    │
└─────────────┘    └──────────────┘    └──────────────┘    └─────────────┘
   原始设计           可交换格式         CPQ 兼容格式        生产环境
```

**关键路径**：CAD → glb（直接或中转） → Blender 优化 + 命名规范化 → 上传

---

## 二、软件环境要求

| 软件 | 推荐版本 | 用途 |
|---|---|---|
| **SolidWorks** | 2022+ | 部分版本支持直接导出 glb（需插件） |
| **CATIA** | V5 R28+ / 3DEXPERIENCE | 通过 fbx 中转 |
| **Pro-E / Creo** | Creo 7+ | 通过 stl/fbx 中转 |
| **Blender** | 3.6 LTS / 4.0+ | 优化 + 减面 + 命名 + 导出 .glb（推荐 Khronos glTF Exporter）|
| **glTF Validator** | 在线 https://github.khronos.org/glTF-Validator/ | 验证导出文件合规性 |
| **gltf-pipeline** | npm i -g gltf-pipeline | Draco 压缩工具（命令行）|

---

## 三、SolidWorks 导出 GLB

### 方式 A：原生导出（SolidWorks 2024+）

1. 打开装配体 `.SLDASM` 或零件 `.SLDPRT`
2. **文件** → **另存为** → 类型选择 `gltf 2.0 (*.glb;*.gltf)`
3. 文件名按规范：`<partNo>-v<version>.glb` (例 `CFG-COMBO-000018-v1.glb`)
4. 高级选项：
   - ✅ 包含材质 + 纹理
   - ✅ 按特征分割 mesh（关键 — 让每个零件成为独立 mesh）
   - ⚠ 限制顶点数 < 50000/mesh
5. 保存 → 检查文件大小 < 10MB

### 方式 B：中转 STEP/FBX → Blender

如 SolidWorks 版本不支持直接 glb：

1. **导出 STEP**：文件 → 另存为 → STEP (.step)
2. 打开 Blender，**文件 → 导入 → STEP**（需安装 STEPper 插件）
   - 或先用 **FreeCAD** 转换 step → fbx，再 Blender 导入 fbx
3. 在 Blender 内重命名 mesh + 优化（见 §五）
4. **文件 → 导出 → glTF 2.0 (.glb)**

### SolidWorks 内设置 mesh 命名

SolidWorks 不直接支持自定义 mesh ID，**导出后必须在 Blender 重命名**。建议建模时给"零件/特征"用规范名（如 `child_a_AgCu` / `weld_seam_01`），导出后在 Blender 自动映射成 `mesh_child_a_AgCu` 等。

---

## 四、CATIA 导出 GLB

CATIA 无原生 glb 支持，**必须**走中转：

### 步骤

1. CATIA：**文件 → 另存为** → 选 `*.fbx` 或 `*.3dxml`
2. 打开 Autodesk FBX Converter（免费工具）
   - 输入：fbx
   - 输出：glb（部分版本需第三方插件，或先转 obj 再 Blender）
3. 推荐：直接在 Blender 导入 fbx → 优化 → 导出 glb

### CATIA 装配体的特殊处理

CATIA Product (`.CATProduct`) 是多层级嵌套：
- 顶层 Product = 整个组件
- 子层 Part = 单个零件 = 后续的 mesh
- 必须 **"扁平化"**（在 CATIA 内 explode 或 Blender 内 Object > Join）后才上传，否则 mesh 数量会爆炸

---

## 五、Blender 优化（必经步骤）

无论 CAD 走哪个路径，**最终都要进 Blender 做 3 件事**：

### 5.1 Mesh 命名规范化（关键）

Blender 内每个 Object 的 name 就是导出 glb 后的 mesh ID。**重命名规则**（详见 [3D产品选配方案.md §六](./3D产品选配方案.md)）：

| 当前 Blender Object Name | 应改为 | 业务归类 |
|---|---|---|
| `Cube.001` | `mesh_base_frame` | DECORATIVE |
| `Part_AgCu_85` | `mesh_child_a` | BOM_ITEM |
| `Cylinder_Cu` | `mesh_child_b` | BOM_ITEM |
| `Torus_Weld` | `mesh_weld_01` | PROCESS_ZONE |
| `Surface_Coating` | `mesh_mat_outer` | MATERIAL_AREA |

**操作**：
1. 在 Outliner 窗口右键 → Rename，或 F2 快捷键
2. 批量重命名：选中多个 → `Object → Rename Active Object` → 用 Python 脚本

```python
# Blender 内 Scripting 标签页执行
for i, obj in enumerate(bpy.context.selected_objects):
    obj.name = f"mesh_child_{chr(97+i)}"  # mesh_child_a, mesh_child_b, ...
```

### 5.2 减面优化（控制顶点数）

**目标**：单 mesh < 50,000 顶点 / 全模型 < 200,000 顶点

操作：
1. 选中高顶点 mesh
2. **Modifier** → **Decimate**（删减细分）
3. Ratio 调到 0.3 ~ 0.5（保留 30-50% 面）
4. Apply Modifier 确认
5. 查看顶点数：**N 面板 → Item → 注释栏** 或 Statistics overlay

### 5.3 Draco 压缩（推荐）

Draco 是 Google 开源的 3D 网格压缩，可减小 glb 文件 30-50%。

**Blender 内**：
1. **文件 → 导出 → glTF 2.0 (.glb)**
2. 导出选项面板：
   - ✅ **Compression** → 启用 Draco
   - Compression Level: 6（默认）
   - 其他保持默认

**命令行替代**：
```bash
npm i -g gltf-pipeline
gltf-pipeline -i model.glb -o model-compressed.glb --draco.compressionLevel=7
```

---

## 六、验证清单（上传前必跑）

### 6.1 文件层面

- [ ] 文件大小 < 10MB
- [ ] 格式为 `.glb` 或 `.gltf`
- [ ] glTF Validator 通过（https://github.khronos.org/glTF-Validator/）
- [ ] 浏览器能加载（拖入 https://sandbox.babylonjs.com/）

### 6.2 Mesh 层面

- [ ] 所有可交互 mesh 按 `mesh_*` 前缀命名
- [ ] BOM_ITEM 子件命名为 `mesh_child_*`
- [ ] 工艺区命名为 `mesh_proc_*` 或 `mesh_weld_*`
- [ ] 材质区命名为 `mesh_mat_*`
- [ ] 装饰类命名为 `mesh_base_*` 或 `mesh_deco_*`
- [ ] 单 mesh 顶点数 < 50,000
- [ ] 总顶点数 < 200,000
- [ ] mesh 数量 1 ≤ N ≤ 50

### 6.3 视觉层面

- [ ] 模型方向正确（Y-up）
- [ ] 比例合理（不会过大/过小撑爆/看不到）
- [ ] 各 mesh 位置合理（无 Z-fighting / 重叠）
- [ ] 材质 / 颜色清晰可辨

### 6.4 业务层面

- [ ] partNo 在 CPQ 的 `mat_part` 表存在
- [ ] 各 BOM_ITEM mesh 对应的 `referenceCode` 在 `mat_bom` 存在
- [ ] PROCESS_ZONE mesh 对应的 `referenceCode` 在 `mat_process` 存在

---

## 七、常见问题

### Q1: SolidWorks 导出 glb 后所有零件合并成 1 个 mesh，无法独立选中？
**A**: 导出时必须勾选"按特征/零件分割 mesh"。或导入 Blender 后用 `Edit Mode → Separate → By Loose Parts` 分离。

### Q2: 文件大小超 10MB 怎么办？
**A**: 三件事：① Blender Decimate 减面 ② 启用 Draco 压缩 ③ 删除装饰类小部件（螺丝/标签等无业务价值的 mesh）。

### Q3: 模型加载后看不到任何东西？
**A**: 可能：① 模型 scale 过大 → 进入相机内部；② 单位错（CAD 用 mm，Blender 用 m）→ 在 Blender Scale by 0.001。

### Q4: 中文字符在 mesh 名里能用吗？
**A**: 技术上可以，但**强烈不推荐**。命名规范统一用英文 + 下划线（如 `mesh_child_a`），中文标签放 CSV 的 `meshLabel` 字段。

### Q5: 有没有自动化脚本？
**A**: Blender Python 脚本示例：

```python
# auto-name-meshes.py
import bpy

# 自动按位置排序命名 BOM_ITEM
bom_objs = [obj for obj in bpy.context.scene.objects if obj.type == 'MESH' and 'part' in obj.name.lower()]
bom_objs.sort(key=lambda o: o.location.x)  # 按 X 坐标排序
for i, obj in enumerate(bom_objs):
    obj.name = f"mesh_child_{chr(97+i)}"

# 自动找焊缝 → 重命名 mesh_weld_*
weld_objs = [obj for obj in bpy.context.scene.objects if 'weld' in obj.name.lower() or 'seam' in obj.name.lower()]
for i, obj in enumerate(weld_objs):
    obj.name = f"mesh_weld_{str(i+1).zfill(2)}"

# 其余归为 base
for obj in bpy.context.scene.objects:
    if obj.type == 'MESH' and not obj.name.startswith('mesh_'):
        obj.name = 'mesh_base_' + obj.name.lower()
```

### Q6: 导出后 mesh 数量增加了（CAD 里 5 个零件，glb 里有 18 个 mesh）？
**A**: 多半是 CAD 把"实体特征"拆分成多个面 mesh。在 Blender 内：
1. 选中所有相关 mesh
2. `Ctrl+J` (Join) 合并为单 mesh
3. 重命名

---

## 八、推荐工作流（每个新 partNo 的标准流程）

```
1. 工程师在 CAD 完成设计
        ↓
2. 导出 step / fbx（中间格式）
        ↓
3. Blender 导入
        ↓
4. 检查 mesh 数量 / 顶点数
        ↓
5. 按规范重命名 mesh（mesh_*）
        ↓
6. Decimate 减面（如顶点超标）
        ↓
7. 启用 Draco 压缩导出 glb
        ↓
8. glTF Validator 验证
        ↓
9. 拖入 https://sandbox.babylonjs.com/ 目视确认
        ↓
10. 登录 CPQ 管理端 → /admin/parts/{partNo}/3d
        ↓
11. 上传 + 配置 mesh 映射（可借助 CSV 模板）
        ↓
12. 试用预览 → 激活
        ↓
13. 报价单页面 🎬 入口生效
```

---

## 九、参考资源

- **Babylon Sandbox**（在线预览 glb）: https://sandbox.babylonjs.com/
- **glTF Validator**: https://github.khronos.org/glTF-Validator/
- **Blender glTF 文档**: https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html
- **Draco 压缩**: https://github.com/google/draco
- **gltf-pipeline**（命令行工具）: https://github.com/CesiumGS/gltf-pipeline
- **SolidWorks glTF Exporter 插件**（社区版）: https://github.com/Glavin001/solidworks-gltf-exporter

---

## 十、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-05-24 | v0.1 | 初版：SolidWorks / CATIA / Pro-E / Blender 工作流 + 验证清单 + 自动化脚本 |
