FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
# 실패할 때만 저장소 조회 로그를 남긴다. 플러그인 조회가 간헐적으로 실패한 원인을 아직 모른다 (TS-0010)
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies --info > /tmp/deps.log 2>&1     || { grep -Ei 'http|resolv|timed out|refused|unknownhost|ssl|status|could not' /tmp/deps.log | tail -80; exit 1; }
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/api-*-SNAPSHOT.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:17-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin api
WORKDIR /app
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
USER 10001
EXPOSE 8080
# JVM이 PID 1로 SIGTERM을 받아 graceful shutdown 한다
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
