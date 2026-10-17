# Retain the existing PostgreSQL major version and Alpine base for existing volumes.
FROM postgres:17-alpine
ARG PGVECTOR_VERSION=0.8.7
RUN apk add --no-cache --virtual .vector-build build-base git \
    && git clone --depth 1 --branch v${PGVECTOR_VERSION} https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector && make OPTFLAGS="" with_llvm=no && make with_llvm=no install \
    && cd / && rm -rf /tmp/pgvector && apk del .vector-build
