# Тонкий e2e-раннер: бьёт HTTP в уже собранный контейнер API (Задание 5, «разные Dockerfile»).
FROM alpine:3.20

RUN apk add --no-cache bash curl jq ffmpeg

WORKDIR /workspace
COPY scripts/e2e-curl.sh /usr/local/bin/e2e-curl.sh
RUN chmod +x /usr/local/bin/e2e-curl.sh

ENV VIDAY_API_BASE=http://api:8080
ENTRYPOINT ["/usr/local/bin/e2e-curl.sh"]
