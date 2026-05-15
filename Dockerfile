# syntax=docker/dockerfile:1.7
# Mirboard 단일 머신 배포용 이미지 (Phase 7-2 / D-39).
#
# 구조:
#   stage 1 (client-build)  Vite 빌드로 client/dist 생성
#   stage 2 (server-build)  Gradle 로 bootJar — client/dist 를 resources/static 으로 복사
#   stage 3 (runtime)       JRE 25 위에서 jar 단일 실행
#
# 빌드는 Fly.io 가 BuildKit 으로 수행. 로컬 검증: `docker build -t mirboard:local .`

############################################
# Stage 1 — Vite 클라 번들
############################################
FROM node:20-alpine AS client-build
WORKDIR /app/client

COPY client/package.json client/package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY client/ ./
RUN npm run build

############################################
# Stage 2 — Spring Boot bootJar
############################################
FROM eclipse-temurin:25-jdk-noble AS server-build
WORKDIR /app

# 1) Gradle wrapper / 루트 빌드 메타 + 서브모듈 빌드 스크립트 먼저 — 의존성만 캐싱.
COPY settings.gradle.kts gradle.properties gradlew ./
COPY gradle gradle
COPY server/build.gradle.kts server/build.gradle.kts
RUN chmod +x gradlew && \
    ./gradlew :server:dependencies --no-daemon --no-configuration-cache > /dev/null 2>&1 || true

# 2) 서버 소스 + 클라 번들 복사 후 bootJar.
COPY server server
COPY --from=client-build /app/client/dist/ server/src/main/resources/static/

RUN ./gradlew :server:bootJar --no-daemon --no-configuration-cache -x test

############################################
# Stage 3 — 런타임 (JRE only)
############################################
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# 비-root 유저로 실행.
RUN useradd --create-home --shell /usr/sbin/nologin --uid 10001 mirboard
USER mirboard

COPY --from=server-build --chown=mirboard:mirboard /app/server/build/libs/*.jar /app/app.jar

EXPOSE 8080

# Spring profile / GC / heap 한도 — Fly machine 의 RAM 75% 까지 사용.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
