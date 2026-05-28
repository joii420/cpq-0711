# CAD 转换 POC 技术验证（v0.4 实验性）

> 🧪 **实验性 · 不实际部署**。本文档是 UG NX `.stp` → `.glb` 转换流水线的**技术可行性验证 + Docker 镜像设计 + 脚本草案**，用于后续阶段 4 启动 POC 时直接落地。
>
> **关联**：[`3D产品选配方案.md` §六 UG NX 工作流](./3D产品选配方案.md#六-ug-nx--step--glb-转换工作链路-v04-主线)

---

## 一、技术栈选型最终决策

| 项 | 选型 | 备选 / 否决原因 |
|---|---|---|
| **STEP 解析** | FreeCAD CLI (Python 脚本) | OCCT C++（集成复杂，二期再做）|
| **几何转 GLB** | Blender headless (Python 脚本) | gltf-pipeline 仅支持已有 GLB 后处理，不能从 STL 直转 |
| **Draco 压缩** | Blender glTF Exporter 内置 Draco | 单独 gltf-pipeline CLI（增加一步，慢）|
| **缩略图渲染** | Blender headless | three.js node-canvas（依赖复杂）|
| **STEP 特征识别** | FreeCAD Part API | OCCT 直调（二期）|
| **容器化** | Docker 多阶段构建 | 裸机部署（运维成本高）|
| **任务队列** | Postgres LISTEN/NOTIFY (项目已有 PG) | RabbitMQ / Redis Stream（新依赖）|
| **Worker 池** | 独立 cpq-cad-worker 服务 + 3 副本 | 嵌入 cpq-backend（OOM 风险）|

**结论**：FreeCAD + Blender 双工具链 + Docker 镜像 + Worker 服务 + PG 队列

---

## 二、转换流水线（5 阶段）

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ 1. 接收任务  │ → │ 2. STEP→STL  │ → │ 3. STL→GLB   │ → │ 4. 缩略图     │ → │ 5. 特征识别   │
│ PG NOTIFY    │   │ FreeCAD      │   │ Blender +    │   │ Blender       │   │ FreeCAD Part │
│              │   │ tessellation │   │ Draco 压缩   │   │ render PNG    │   │ shape 解析   │
│ 5s           │   │ 10-30s       │   │ 5-15s        │   │ 3-8s          │   │ 5-20s        │
└──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
                                                                                 │
                                                                                 ▼
                                                                  extracted_features JSONB
                                                                  ← 写入 mat_part_glb_conversion
                                                                    (管理员审核后入 mat_feature)
总耗时: 30-90s / 中等复杂度模型 (50MB STEP)
```

---

## 三、Dockerfile 设计（多阶段构建）

```dockerfile
# cpq-backend/docker/cad-converter/Dockerfile
# 多阶段构建：基础层 (FreeCAD) + 渲染层 (Blender) + 应用层 (Python 脚本)

# ===================================================================
# 阶段 1: FreeCAD 基础层 (1.5GB)
# ===================================================================
FROM ubuntu:22.04 AS freecad-base
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    freecad-python3 \
    python3-pip \
    libgl1 libxrender1 libxi6 libsm6 \
    && rm -rf /var/lib/apt/lists/*

# ===================================================================
# 阶段 2: Blender 添加层 (+ 500MB)
# ===================================================================
FROM freecad-base AS blender-added
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && wget -q https://download.blender.org/release/Blender4.0/blender-4.0.2-linux-x64.tar.xz \
    && tar -xJf blender-4.0.2-linux-x64.tar.xz -C /opt \
    && mv /opt/blender-4.0.2-linux-x64 /opt/blender \
    && ln -s /opt/blender/blender /usr/local/bin/blender \
    && rm blender-4.0.2-linux-x64.tar.xz

# ===================================================================
# 阶段 3: 应用层 (最终镜像 ~2.2GB)
# ===================================================================
FROM blender-added AS final

# 安装 Python 依赖（任务队列 + 对象存储客户端）
RUN pip3 install --no-cache-dir \
    psycopg2-binary==2.9.9 \
    boto3==1.34.0 \
    pillow==10.1.0 \
    requests==2.31.0

# 复制转换脚本
COPY scripts/ /opt/cad-converter/scripts/
COPY worker.py /opt/cad-converter/worker.py

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s \
    CMD python3 -c "import psycopg2; psycopg2.connect('${DATABASE_URL}').close()" || exit 1

# 资源限制（运行时通过 docker run --memory）
ENV WORKER_CONCURRENCY=1
ENV CONVERSION_TIMEOUT_SEC=300
ENV TESSELLATION_PRECISION=1.5

WORKDIR /opt/cad-converter
CMD ["python3", "worker.py"]
```

**镜像大小预估**：~2.2 GB
- FreeCAD base: ~1.5 GB
- Blender 4.0: ~500 MB
- Python deps + 脚本: ~200 MB

**资源配置（运行时推荐）**：
- CPU: 2 核
- 内存: 4 GB（复杂装配体需 8 GB）
- 临时磁盘: 5 GB（中间文件）
- 副本数: 3（按 CPU 调整）

---

## 四、转换脚本设计

### 4.1 主入口 Worker（Python，监听 PG NOTIFY）

```python
# worker.py
import os, time, json, tempfile, subprocess, traceback
import psycopg2
import psycopg2.extensions
import boto3

DB_URL = os.environ['DATABASE_URL']
S3 = boto3.client('s3', endpoint_url=os.environ['OSS_ENDPOINT'])

def main():
    conn = psycopg2.connect(DB_URL)
    conn.set_isolation_level(psycopg2.extensions.ISOLATION_LEVEL_AUTOCOMMIT)
    cur = conn.cursor()
    cur.execute("LISTEN cad_conversion_queue;")
    print("[Worker] Ready, listening...")
    while True:
        conn.poll()
        while conn.notifies:
            n = conn.notifies.pop(0)
            process_task(json.loads(n.payload), cur)
        time.sleep(1)

def process_task(payload, cur):
    conversion_id = payload['conversion_id']
    source_file_id = payload['source_file_id']
    start_time = time.time()
    
    cur.execute("UPDATE mat_part_glb_conversion SET status='RUNNING', started_at=NOW() WHERE id=%s", (conversion_id,))
    
    workdir = tempfile.mkdtemp(prefix='cpq-cad-')
    try:
        # 1. 下载 STEP 文件
        cur.execute("SELECT file_url FROM mat_part_source_file WHERE id=%s", (source_file_id,))
        stp_url = cur.fetchone()[0]
        stp_path = f"{workdir}/input.stp"
        download_from_oss(stp_url, stp_path)
        
        # 2. STEP → STL (FreeCAD)
        stl_path = f"{workdir}/output.stl"
        subprocess.run(['freecadcmd', 'scripts/stp-to-stl.py', '--', stp_path, stl_path],
                       check=True, timeout=120)
        
        # 3. STL → GLB (Blender + Draco)
        glb_path = f"{workdir}/output.glb"
        subprocess.run(['blender', '--background', '--python', 'scripts/stl-to-glb.py',
                        '--', stl_path, glb_path], check=True, timeout=180)
        
        # 4. 缩略图
        thumb_path = f"{workdir}/thumb.png"
        subprocess.run(['blender', '--background', '--python', 'scripts/render-thumb.py',
                        '--', glb_path, thumb_path], check=True, timeout=60)
        
        # 5. 特征识别
        features_json = f"{workdir}/features.json"
        subprocess.run(['freecadcmd', 'scripts/extract-features.py', '--', stp_path, features_json],
                       check=True, timeout=60)
        with open(features_json) as f:
            features = json.load(f)
        
        # 6. 上传到 OSS
        glb_oss_url = upload_to_oss(glb_path, f"cpq-3d-glb/{payload['part_no']}/v{payload['version']}/model.glb")
        thumb_oss_url = upload_to_oss(thumb_path, f"cpq-3d-glb/{payload['part_no']}/v{payload['version']}/thumb.png")
        
        # 7. 更新 DB
        duration_ms = int((time.time() - start_time) * 1000)
        cur.execute("""
            UPDATE mat_part_glb_conversion
            SET status='SUCCESS', finished_at=NOW(), duration_ms=%s,
                extracted_features=%s
            WHERE id=%s
        """, (duration_ms, json.dumps(features), conversion_id))
        
        # 8. INSERT mat_part_model + mat_part_source_file (GLB role) + thumbnail
        insert_model_and_glb_records(cur, payload, glb_oss_url, thumb_oss_url)
        
        # 9. 通知管理员
        notify_admin(payload, 'SUCCESS', duration_ms)
        print(f"[Worker] OK {payload['part_no']} v{payload['version']} {duration_ms}ms")
    
    except subprocess.TimeoutExpired:
        cur.execute("UPDATE mat_part_glb_conversion SET status='TIMEOUT', error_message='转换超时' WHERE id=%s", (conversion_id,))
        notify_admin(payload, 'TIMEOUT')
    except Exception as e:
        cur.execute("UPDATE mat_part_glb_conversion SET status='FAILED', error_message=%s WHERE id=%s",
                    (str(e), conversion_id))
        notify_admin(payload, 'FAILED', error=str(e))
        traceback.print_exc()
    finally:
        cleanup_workdir(workdir)

if __name__ == '__main__':
    main()
```

### 4.2 FreeCAD STEP → STL 脚本

```python
# scripts/stp-to-stl.py
import sys, FreeCAD, Import, Mesh

input_path = sys.argv[1]
output_path = sys.argv[2]

# 打开 STEP
Import.open(input_path)
doc = FreeCAD.ActiveDocument

# 合并所有形状并 mesh 化
objs = [obj for obj in doc.Objects if hasattr(obj, 'Shape')]
Mesh.export(objs, output_path, 
            tessellation=1.5,        # 0.1 (高精度,慢) ~ 5.0 (低精度,快)
            opt_simplify=True)        # 简化共面三角形

print(f"[FreeCAD] STEP→STL done: {output_path}")
```

### 4.3 Blender STL → GLB + Draco 压缩

```python
# scripts/stl-to-glb.py
import bpy, sys, os

# 解析参数
argv = sys.argv[sys.argv.index("--") + 1:]
input_stl = argv[0]
output_glb = argv[1]

# 清空默认场景
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete()

# 导入 STL
bpy.ops.import_mesh.stl(filepath=input_stl)
obj = bpy.context.selected_objects[0]

# 设置 PBR 材质（基础灰色 + 金属感）
mat = bpy.data.materials.new(name="DefaultPBR")
mat.use_nodes = True
bsdf = mat.node_tree.nodes['Principled BSDF']
bsdf.inputs['Base Color'].default_value = (0.6, 0.6, 0.65, 1)
bsdf.inputs['Metallic'].default_value = 0.5
bsdf.inputs['Roughness'].default_value = 0.4
obj.data.materials.append(mat)

# 减面（如果顶点数 > 50000）
if len(obj.data.vertices) > 50000:
    decimate_mod = obj.modifiers.new(name="Decimate", type='DECIMATE')
    decimate_mod.ratio = 0.5
    bpy.ops.object.modifier_apply(modifier="Decimate")

# 导出 GLB + Draco 压缩
bpy.ops.export_scene.gltf(
    filepath=output_glb,
    export_format='GLB',
    export_draco_mesh_compression_enable=True,
    export_draco_mesh_compression_level=7,    # 0-10, 7 是平衡点
    export_draco_position_quantization=14,
    export_apply=True,
)
print(f"[Blender] STL→GLB done: {output_glb}")
```

### 4.4 缩略图渲染（Blender）

```python
# scripts/render-thumb.py
import bpy, sys, os

argv = sys.argv[sys.argv.index("--") + 1:]
input_glb = argv[0]
output_png = argv[1]
size = 256

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete()

# 导入 GLB
bpy.ops.import_scene.gltf(filepath=input_glb)

# 设置相机 45° 等距视角
bpy.ops.object.camera_add(location=(5, -5, 5), rotation=(1.1, 0, 0.78))
bpy.context.scene.camera = bpy.context.object

# 设置光源（柔光）
bpy.ops.object.light_add(type='SUN', location=(5, -5, 10))
bpy.context.object.data.energy = 3

# 设置背景透明
bpy.context.scene.render.film_transparent = True

# 渲染
bpy.context.scene.render.image_settings.file_format = 'PNG'
bpy.context.scene.render.resolution_x = size
bpy.context.scene.render.resolution_y = size
bpy.context.scene.render.filepath = output_png
bpy.ops.render.render(write_still=True)

print(f"[Blender] Thumbnail done: {output_png}")
```

### 4.5 特征自动识别（FreeCAD Part API）

```python
# scripts/extract-features.py
import sys, json, FreeCAD, Import, Part

input_stp = sys.argv[1]
output_json = sys.argv[2]

Import.open(input_stp)
doc = FreeCAD.ActiveDocument
features = []

for obj in doc.Objects:
    if not hasattr(obj, 'Shape'):
        continue
    shape = obj.Shape
    for face_idx, face in enumerate(shape.Faces):
        surf = face.Surface
        # 识别圆柱面（孔特征候选）
        if surf.TypeId == 'Part::GeomCylinder':
            radius = surf.Radius
            diameter = radius * 2
            bbox = face.BoundBox
            features.append({
                'feature_type_code': 'HOLE',     # 待识别是否螺纹
                'attributes': {
                    'diameter_mm': round(diameter, 3),
                    'depth_mm': round(bbox.ZMax - bbox.ZMin, 3),
                },
                'geometry_ref': {
                    'bbox': [bbox.XMin, bbox.YMin, bbox.ZMin, bbox.XMax, bbox.YMax, bbox.ZMax],
                    'center': [(bbox.XMin+bbox.XMax)/2, (bbox.YMin+bbox.YMax)/2, (bbox.ZMin+bbox.ZMax)/2],
                    'face_index': face_idx,
                }
            })
        # 识别平面（焊缝/装饰面候选）
        elif surf.TypeId == 'Part::GeomPlane':
            if face.Area > 100:   # > 100 mm² 才考虑
                features.append({
                    'feature_type_code': 'SURFACE',
                    'attributes': {
                        'area_mm2': round(face.Area, 2),
                    },
                    'geometry_ref': {
                        'normal': list(surf.normal(0, 0)),
                        'face_index': face_idx,
                    }
                })

with open(output_json, 'w') as f:
    json.dump(features, f, indent=2)

print(f"[FreeCAD] Extracted {len(features)} features")
```

**重要**：特征**不自动入 mat_feature 表**，仅写入 `mat_part_glb_conversion.extracted_features` 待管理员审核。

---

## 五、性能 / 内存 / 失败率预估

### 5.1 性能基准（基于公开 STEP 案例 + Worker 4 vCPU / 8GB RAM）

| 模型类型 | 顶点数 | STEP 大小 | 总耗时 | 备注 |
|---|---|---|---|---|
| 简单零件（单 box）| < 100 | < 100 KB | 8-15s | 加载/启动开销主导 |
| 标准件（螺栓螺母）| 1k-5k | < 500 KB | 15-25s | 典型场景 |
| 中等装配（10 部件）| 20k-50k | 2-10 MB | 30-60s | 推荐区间 |
| 复杂装配（电机/泵）| 100k-300k | 20-50 MB | 60-120s | 接近 5 分钟超时 |
| 极大装配（整车）| > 500k | > 100 MB | **> 5 分钟超时** | **不支持**，需 UG 内拆分上传 |

### 5.2 内存占用

| 阶段 | 内存峰值 |
|---|---|
| FreeCAD STEP 解析 | 1-3 GB（与 STEP 大小线性相关）|
| Blender STL 导入 + Draco | 1-2 GB |
| Blender 缩略图渲染 | 500 MB - 1 GB |
| Python Worker 主进程 | < 200 MB |
| **峰值合计** | **3-5 GB（推荐 8 GB 配额）**|

### 5.3 失败率预估（基于行业经验）

| 失败类型 | 占比 | 处理 |
|---|---|---|
| STEP AP 版本不支持 | ~3% | 提示工程师改用 AP214/AP242 |
| 文件损坏 / 不完整 | ~1% | 重新导出 |
| 内存溢出（超大装配） | ~2% | 自动降级 tessellation 重试 |
| 超时（> 5 分钟）| ~1% | 提示拆分 / 简化模型 |
| FreeCAD/Blender 进程崩溃 | ~0.5% | 自动重试 1 次 |
| **预期成功率** | **~92.5%** | 余下手动干预 |

---

## 六、POC 落地路径（5 步）

### Step 1: 本地单机验证（1-2 天）
- 装 FreeCAD + Blender 命令行
- 跑通 4 个脚本（手动调用）
- 用 10 个真实 .stp 文件测试
- 记录耗时 / 失败率 / 失败原因

### Step 2: 构建 Docker 镜像（1-2 天）
- 写 Dockerfile + 测试构建
- 验证镜像内 FreeCAD / Blender 命令可用
- 镜像大小优化（多阶段 / .dockerignore）

### Step 3: Worker 服务化（2-3 天）
- 实现 worker.py 主循环 + PG NOTIFY 监听
- 集成对象存储客户端（MinIO/OSS/S3）
- 写 docker-compose.yml + Worker 副本测试

### Step 4: 后端 API 接入（3-5 天）
- 新建 Part3DResource + Part3DService
- 上传 .prt + .stp 端点 + INSERT mat_part_source_file
- PG NOTIFY 触发 worker
- WebSocket 通知前端进度（可选）

### Step 5: 前端管理端集成（2-3 天）
- `v0.4-3D源文件上传与转换原型.html` → 真实项目实现
- 上传向导 + 转换状态实时显示
- 特征审核界面（管理员确认 extracted_features 后入库）

**POC 总工时**：9-15 天（不含 K8s 部署 + 高可用 + 监控）

---

## 七、生产部署考量（POC 完成后）

| 项 | 方案 |
|---|---|
| **K8s 部署** | cpq-cad-worker Deployment，副本数 3，HPA 按 CPU/队列长度自动扩缩 |
| **存储隔离** | 3 个 OSS Bucket：cpq-ugnx-source / cpq-stp-source / cpq-3d-glb |
| **监控** | Prometheus 采集（转换时长 / 失败率 / 队列积压）+ Grafana 看板 |
| **告警** | 失败率 > 5% / 队列积压 > 50 / Worker 容器 OOM 触发钉钉 |
| **日志** | ELK 集中（每次转换的 FreeCAD/Blender 输出归档保留 30 天）|
| **审计** | 文件下载签名 URL + 审计日志（谁/何时/下载哪个文件）|
| **备份** | OSS 跨区域复制（cpq-ugnx-source / cpq-stp-source 双备份）|

---

## 八、风险与回退

| 风险 | 缓解 |
|---|---|
| FreeCAD 版本升级破坏脚本 | Docker 镜像固定 FreeCAD 版本 + 测试覆盖 |
| Blender 升级 glTF Exporter API 变化 | 同上 |
| STEP 文件含敏感设计信息 | OSS 服务端加密 + 签名 URL + 审计 |
| 转换排队延迟 | Worker 副本数动态扩缩 + 前端非阻塞（异步通知）|
| Docker 镜像漏洞 | 定期 trivy 扫描 + ubuntu base 升级 |
| 工程师 UG 不会导出 .stp | 提供详细操作手册 + 视频教程 + 上传向导内嵌指引 |

---

## 九、变更记录

| 日期 | 变更 |
|---|---|
| 2026-05-26 | 初稿：Dockerfile / 4 个脚本 / 性能预估 / POC 路径 |
