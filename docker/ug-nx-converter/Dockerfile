# CPQ UG NX → STEP → GLB 转换 Worker
# 基础镜像: Ubuntu 22.04 + FreeCAD + Blender + Python + 任务队列消费者
# 详见: docs/CAD转换POC-技术验证.md

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

# 基础工具 + 编译依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg2 \
        python3 python3-pip python3-venv \
        software-properties-common \
        libgl1 libglu1-mesa libxi6 libxrender1 libxxf86vm1 libsm6 libxext6 \
    && rm -rf /var/lib/apt/lists/*

# FreeCAD 0.21+ (含 Part / Mesh / Import 模块)
RUN add-apt-repository -y ppa:freecad-maintainers/freecad-stable \
    && apt-get update \
    && apt-get install -y --no-install-recommends freecad-python3 \
    && rm -rf /var/lib/apt/lists/*

# Blender 4.x (headless 模式)
RUN cd /opt && \
    wget -q https://download.blender.org/release/Blender4.0/blender-4.0.2-linux-x64.tar.xz && \
    tar -xJf blender-4.0.2-linux-x64.tar.xz && \
    rm blender-4.0.2-linux-x64.tar.xz && \
    ln -s /opt/blender-4.0.2-linux-x64/blender /usr/local/bin/blender

# Python 依赖（任务队列 + S3 + Draco 工具）
COPY requirements.txt /app/requirements.txt
RUN pip3 install --no-cache-dir -r /app/requirements.txt

# 工作脚本
WORKDIR /app
COPY worker.py stp-to-stl.py stl-to-glb.py extract-features.py /app/

# 验证安装
RUN freecad --version && blender --version && python3 --version

# 默认入口：消费者
CMD ["python3", "worker.py"]
