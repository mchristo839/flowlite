FROM alpine:3.20
ARG PB_VERSION=0.40.5
RUN apk add --no-cache ca-certificates sqlite wget unzip \
 && wget -q -O /tmp/pb.zip https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_amd64.zip \
 && wget -q -O /tmp/checksums.txt https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/checksums.txt \
 && cd /tmp && grep "pocketbase_${PB_VERSION}_linux_amd64.zip" checksums.txt | sed 's#  .*#  /tmp/pb.zip#' | sha256sum -c - \
 && unzip /tmp/pb.zip pocketbase -d /pb && rm /tmp/pb.zip /tmp/checksums.txt
COPY pb_migrations /pb/pb_migrations
COPY pb_hooks /pb/pb_hooks
EXPOSE 8090
CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", "--migrationsDir=/pb/pb_migrations", "--hooksDir=/pb/pb_hooks"]
