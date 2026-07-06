# =============================================================================
# Stage 1: Build React frontend
# =============================================================================
FROM node:18-alpine AS frontend-builder
WORKDIR /workspace/platform/frontend

COPY platform/frontend/package*.json ./
# --legacy-peer-deps 兼容部分依赖的 peerDeps 声明
RUN npm ci --legacy-peer-deps

COPY platform/frontend/ ./
RUN npm run build

# =============================================================================
# Stage 2: Build Java（核心引擎 jar + Spring Boot jar）
# =============================================================================
FROM eclipse-temurin:17-jdk-alpine AS java-builder
WORKDIR /workspace

# ── 构建根项目核心 jar ──────────────────────────────────────
COPY build.gradle gradlew ./
COPY gradle/ gradle/
COPY src/ src/
RUN chmod +x gradlew && ./gradlew jar -x test --no-daemon --quiet

# ── 拷贝 platform 源码 ──────────────────────────────────────
COPY platform/build.gradle platform/settings.gradle platform/
COPY platform/gradle/ platform/gradle/
COPY platform/gradlew platform/gradlew.bat platform/
COPY platform/src/ platform/src/
RUN chmod +x platform/gradlew

# ── 将前端构建产物嵌入 Spring Boot static 资源 ─────────────
COPY --from=frontend-builder /workspace/platform/frontend/dist/ \
     platform/src/main/resources/static/

# ── 构建 Spring Boot bootJar ───────────────────────────────
WORKDIR /workspace/platform
RUN ./gradlew bootJar -x test --no-daemon --quiet

# =============================================================================
# Stage 3: 运行时镜像（最小化）
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S javacg2 && adduser -S javacg2 -G javacg2

COPY --from=java-builder /workspace/platform/build/libs/javacg2-platform.jar app.jar
RUN chown javacg2:javacg2 app.jar

USER javacg2

VOLUME ["/app/data"]
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
