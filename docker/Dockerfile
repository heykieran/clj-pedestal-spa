FROM openjdk:8-stretch
ARG ALLOC_BUILD_ID

COPY entrypoint.sh /sbin/entrypoint.sh
RUN chmod 755 /sbin/entrypoint.sh

EXPOSE 8081/tcp

COPY deploy /app

WORKDIR /app

ENV ALLOC_KEYSTORE_LOCATION=/app/local/jetty-keystore \
    ALLOC_KEYSTORE_PASSWORD=password \
    ALLOC_AUTH_DEBUG_JWT_KEY=garbage \
    ALLOC_SESSION_STORE_KEY=bogusbogus123456 \
    ALLOC_PORT=8080 \
    ALLOC_SSL_PORT=8081 \
    ALLOC_BUILD_ID=${ALLOC_BUILD_ID:-v.dck.dev}

ENTRYPOINT ["/sbin/entrypoint.sh"]
