FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew clean bootJar --no-daemon \
    && JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /tmp/app.jar


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ENV TZ=Asia/Seoul

COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 8080
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]