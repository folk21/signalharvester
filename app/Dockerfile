FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN sh ./gradlew :app:installDist --no-daemon --no-watch-fs

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 signalharvester \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin signalharvester
WORKDIR /opt/signalharvester
COPY --from=builder --chown=10001:10001 /workspace/app/build/install/app/ ./
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["bin/app"]
