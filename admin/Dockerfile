FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY common-entity ./common-entity
COPY admin ./admin

WORKDIR /workspace/admin

RUN chmod +x ./gradlew \
    && ./gradlew clean bootJar --no-daemon \
    && test -f build/libs/admin-app.jar \
    && cp build/libs/admin-app.jar /tmp/app.jar


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ENV TZ=Asia/Seoul

COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 8080
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]