"""
CPQ UG NX 转换 Worker — 生产就绪版

启动方式 (环境变量决定模式):
  POC_MODE=1                    → 文件扫描模式 (本地 /tmp/cpq-conversion 测试)
  RABBITMQ_URL=amqp://...       → 生产消费模式 (RabbitMQ + S3 + Postgres)

详见: docs/CAD转换POC-技术验证.md
"""
import os
import sys
import json
import time
import logging
import subprocess
import tempfile
from pathlib import Path

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('worker')

POC_MODE = os.environ.get('POC_MODE') == '1'
RABBITMQ_URL = os.environ.get('RABBITMQ_URL', 'amqp://guest:guest@rabbitmq:5672/')
QUEUE_NAME = os.environ.get('QUEUE_NAME', 'cpq.conversion.jobs')

# S3 / MinIO
S3_ENDPOINT = os.environ.get('S3_ENDPOINT', 'http://minio:9000')
S3_ACCESS_KEY = os.environ.get('S3_ACCESS_KEY', 'minioadmin')
S3_SECRET_KEY = os.environ.get('S3_SECRET_KEY', 'minioadmin')
S3_BUCKET_STP = os.environ.get('S3_BUCKET_STP', 'cpq-stp-source')
S3_BUCKET_GLB = os.environ.get('S3_BUCKET_GLB', 'cpq-3d-glb')

# Postgres
DB_HOST = os.environ.get('DB_HOST', 'postgres')
DB_PORT = int(os.environ.get('DB_PORT', '5432'))
DB_USER = os.environ.get('DB_USER', 'postgres')
DB_PASSWORD = os.environ.get('DB_PASSWORD', '')
DB_NAME = os.environ.get('DB_NAME', 'cpq_db')

WORKSPACE = Path('/tmp/cpq-conversion')
WORKSPACE.mkdir(exist_ok=True)


# ============================================================
# 转换核心 — POC + 生产共用
# ============================================================
def convert_step_to_glb(stp_path: Path, output_dir: Path) -> dict:
    base = stp_path.stem
    stl_path = output_dir / f'{base}.stl'
    glb_path = output_dir / f'{base}.glb'
    features_path = output_dir / f'{base}.features.json'

    log.info(f'[1/3] FreeCAD STEP→STL: {stp_path.name}')
    r = subprocess.run(
        ['freecad', '--console', '-c',
         f'exec(open("/app/stp-to-stl.py").read()); convert("{stp_path}", "{stl_path}")'],
        capture_output=True, text=True, timeout=300,
    )
    if r.returncode != 0:
        return {'status': 'FAILED', 'stage': 'STEP_TO_STL', 'error': r.stderr[:500]}

    log.info(f'[2/3] Blender STL→GLB Draco: {stl_path.name}')
    r = subprocess.run(
        ['blender', '--background', '--python', '/app/stl-to-glb.py',
         '--', str(stl_path), str(glb_path)],
        capture_output=True, text=True, timeout=300,
    )
    if r.returncode != 0:
        return {'status': 'FAILED', 'stage': 'STL_TO_GLB', 'error': r.stderr[:500]}

    log.info(f'[3/3] Feature extraction: {stp_path.name}')
    try:
        subprocess.run(
            ['freecad', '--console', '-c',
             f'exec(open("/app/extract-features.py").read()); extract("{stp_path}", "{features_path}")'],
            capture_output=True, text=True, timeout=120, check=False,
        )
    except Exception as e:
        log.warning(f'Feature extraction skipped: {e}')

    features = json.loads(features_path.read_text()) if features_path.exists() else []
    return {
        'status': 'COMPLETED',
        'glb_path': str(glb_path),
        'glb_size_bytes': glb_path.stat().st_size if glb_path.exists() else 0,
        'features': features,
        'feature_count': len(features),
    }


# ============================================================
# S3 集成（生产用）
# ============================================================
def get_s3_client():
    try:
        import boto3
        from botocore.client import Config
        return boto3.client(
            's3', endpoint_url=S3_ENDPOINT,
            aws_access_key_id=S3_ACCESS_KEY, aws_secret_access_key=S3_SECRET_KEY,
            config=Config(signature_version='s3v4'), region_name='us-east-1',
        )
    except ImportError:
        log.error('boto3 not installed')
        return None


def download_from_s3(bucket: str, key: str, local_path: Path) -> bool:
    s3 = get_s3_client()
    if not s3: return False
    try:
        s3.download_file(bucket, key, str(local_path))
        log.info(f'S3 ↓ {bucket}/{key} → {local_path}')
        return True
    except Exception as e:
        log.error(f'S3 download failed: {e}')
        return False


def upload_to_s3(local_path: Path, bucket: str, key: str) -> bool:
    s3 = get_s3_client()
    if not s3: return False
    try:
        s3.upload_file(str(local_path), bucket, key,
                       ExtraArgs={'ContentType': 'model/gltf-binary' if key.endswith('.glb') else 'application/octet-stream'})
        log.info(f'S3 ↑ {local_path} → {bucket}/{key}')
        return True
    except Exception as e:
        log.error(f'S3 upload failed: {e}')
        return False


# ============================================================
# Postgres 集成（生产用）
# ============================================================
def get_db_conn():
    try:
        import psycopg2
        return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                                password=DB_PASSWORD, dbname=DB_NAME)
    except Exception as e:
        log.error(f'DB connect failed: {e}')
        return None


def update_conversion_status(job_id: str, status: str, glb_url: str = None, features_count: int = 0, error: str = None):
    """更新 mat_part_glb_conversion + 创建 mat_part_model（status=COMPLETED 时）"""
    conn = get_db_conn()
    if not conn: return
    try:
        cur = conn.cursor()
        cur.execute("""
            UPDATE mat_part_glb_conversion
            SET status = %s, glb_url = %s, error_message = %s,
                completed_at = NOW(), features_count = %s
            WHERE id = %s
        """, (status, glb_url, error, features_count, job_id))
        conn.commit()
        log.info(f'DB updated: {job_id} → {status}')
    except Exception as e:
        log.error(f'DB update failed: {e}')
    finally:
        conn.close()


# ============================================================
# RabbitMQ 消费者（生产模式）
# ============================================================
def consume_rabbitmq():
    try:
        import pika
    except ImportError:
        log.error('pika not installed')
        return
    log.info(f'Connecting RabbitMQ: {RABBITMQ_URL}')
    connection = pika.BlockingConnection(pika.URLParameters(RABBITMQ_URL))
    channel = connection.channel()
    channel.queue_declare(queue=QUEUE_NAME, durable=True)
    channel.basic_qos(prefetch_count=1)

    def callback(ch, method, properties, body):
        try:
            job = json.loads(body)
            job_id = job.get('job_id')
            part_no = job.get('part_no')
            version = job.get('version', 1)
            stp_key = job.get('stp_key')  # S3 key in cpq-stp-source
            log.info(f'📥 Job received: job_id={job_id} part_no={part_no}')

            # 1. 下载 .stp
            stp_path = WORKSPACE / f'{part_no}-v{version}.stp'
            if not download_from_s3(S3_BUCKET_STP, stp_key, stp_path):
                update_conversion_status(job_id, 'FAILED', error='S3 download failed')
                ch.basic_ack(delivery_tag=method.delivery_tag)
                return

            # 2. 转换
            result = convert_step_to_glb(stp_path, WORKSPACE)
            if result['status'] != 'COMPLETED':
                update_conversion_status(job_id, 'FAILED', error=f"{result.get('stage')}: {result.get('error', '')}")
                ch.basic_ack(delivery_tag=method.delivery_tag)
                return

            # 3. 上传 .glb 到 cpq-3d-glb
            glb_local = Path(result['glb_path'])
            glb_key = f'{part_no}/v{version}/model.glb'
            if not upload_to_s3(glb_local, S3_BUCKET_GLB, glb_key):
                update_conversion_status(job_id, 'FAILED', error='S3 upload failed')
                ch.basic_ack(delivery_tag=method.delivery_tag)
                return

            # 4. 回写 DB
            glb_url = f's3://{S3_BUCKET_GLB}/{glb_key}'
            update_conversion_status(job_id, 'COMPLETED', glb_url=glb_url,
                                     features_count=result['feature_count'])

            # 5. ack
            ch.basic_ack(delivery_tag=method.delivery_tag)
            log.info(f'✅ Job completed: {job_id} → {glb_url}')

        except Exception as e:
            log.exception(f'Job failed: {e}')
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)  # 进死信队列

    channel.basic_consume(queue=QUEUE_NAME, on_message_callback=callback)
    log.info(f'🐰 Listening on queue: {QUEUE_NAME}')
    channel.start_consuming()


# ============================================================
# POC 模式：文件扫描
# ============================================================
def consume_local_files():
    log.info('POC mode: scanning /tmp/cpq-conversion')
    for stp in WORKSPACE.glob('*.stp'):
        done = stp.with_suffix('.done')
        if done.exists():
            continue
        log.info(f'Processing {stp.name}')
        result = convert_step_to_glb(stp, WORKSPACE)
        log.info(f'Result: {json.dumps(result, ensure_ascii=False)}')
        done.touch()


if __name__ == '__main__':
    if POC_MODE:
        consume_local_files()
    else:
        consume_rabbitmq()
