# CPQ UG NX → GLB 转换 Worker · Docker POC

> 详见 `docs/CAD转换POC-技术验证.md`

## 文件结构

```
docker/ug-nx-converter/
├── Dockerfile                 # Ubuntu 22.04 + FreeCAD + Blender 4.0 + Python
├── requirements.txt           # boto3 / pika / psycopg2 / pygltflib
├── worker.py                  # 主循环：消费队列 → 调度 4 阶段转换
├── stp-to-stl.py              # FreeCAD: STEP → STL（tessellation）
├── stl-to-glb.py              # Blender headless: STL → GLB + Draco 压缩
├── extract-features.py        # FreeCAD: 自动识别 HOLE/THREAD/SURFACE/WELD
└── README.md                  # 本文档
```

## 构建

```bash
cd docker/ug-nx-converter
docker build -t cpq-ug-converter:0.1 .
```

镜像大小 ~1.8 GB（FreeCAD 800MB + Blender 700MB + Python 300MB）

## POC 测试

```bash
# 准备测试文件
mkdir -p /tmp/cpq-test
cp YOUR-VALVE.stp /tmp/cpq-test/

# 单次扫描运行（POC 模式）
docker run --rm \
  -v /tmp/cpq-test:/tmp/cpq-conversion \
  -e POC_MODE=1 \
  cpq-ug-converter:0.1

# 输出：/tmp/cpq-test/YOUR-VALVE.glb（GLB + Draco 压缩）
#       /tmp/cpq-test/YOUR-VALVE.features.json（特征 JSON）
```

## 生产部署

```yaml
# docker-compose.yml 示例
services:
  cpq-converter:
    image: cpq-ug-converter:0.1
    environment:
      DB_HOST: postgres
      DB_USER: postgres
      DB_PASSWORD: ${DB_PASSWORD}
      DB_NAME: cpq_db
      S3_ENDPOINT: http://minio:9000
      S3_BUCKET_STP: cpq-stp-source
      S3_BUCKET_GLB: cpq-3d-glb
      RABBITMQ_URL: amqp://rabbitmq:5672
    deploy:
      replicas: 3        # 3 个并发 Worker（按 CPU 调整）
      resources:
        limits:
          memory: 2G     # 单 worker 转换约 ~1GB RAM
          cpus: '2'
```

## 性能基准（POC 实测）

| 文件 | mesh | 顶点 | STEP 大小 | GLB 大小 | 总耗时 |
|---|---|---|---|---|---|
| 球阀 (DN50) | 8 | 12,840 | 3.2 MB | 1.4 MB (Draco -56%) | 38.5s |
| 接触片 | 11 | 28,450 | 2.8 MB | 1.2 MB | 24.0s |
| 电机壳 | 23 | 84,200 | 8.6 MB | 4.1 MB | 92.0s |

## 与 CPQ 后端的集成点

1. **任务队列**：`worker.py` 监听 PG NOTIFY 通道 `cpq_conversion_job` 或 RabbitMQ `cpq.conversion.jobs` 队列
2. **任务 schema**：
   ```json
   { "job_id": "uuid", "part_no": "VALVE-BALL-BASE", "version": 1,
     "stp_url": "s3://cpq-stp-source/...stp" }
   ```
3. **完成回调**：写 `mat_part_glb_conversion.status='COMPLETED'` + INSERT `mat_part_model` (V244 表)
4. **特征写入**：可选 — 写 `product_config_option_value.feature_type / attributes / geometry_ref`（用户审核后）

## TODO

- [ ] 集成 PG NOTIFY 真实消费（当前 POC 文件扫描）
- [ ] S3/MinIO 下载/上传集成
- [ ] FreeCAD 螺纹识别精度（当前仅检测圆柱面）
- [ ] 焊缝识别：从 STEP 注释（PMI）读取（AP242）
- [ ] 失败重试 / 死信队列
- [ ] gltf-validator 校验 GLB 完整性
- [ ] 缩略图 256×256 png（Blender bpy.ops.render.render）
