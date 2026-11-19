# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89
FROM python:3.12.12-slim-bookworm@sha256:2986c55feb36e6cae00fa1fefb454283e4b33f35e75ff8bdd123b134130be301

ENV LC_ALL=C.UTF-8
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONHASHSEED=0
ENV TZ=UTC

COPY tools/store-assets/requirements.txt /tmp/store-assets-requirements.txt
RUN python3 -m pip install \
        --disable-pip-version-check \
        --no-cache-dir \
        --requirement /tmp/store-assets-requirements.txt \
    && python3 -c 'import PIL; assert PIL.__version__ == "13.0.0"'

WORKDIR /workspace
ENTRYPOINT ["python3"]
