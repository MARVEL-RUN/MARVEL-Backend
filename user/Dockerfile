FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# user 배포에 필요한 내역만 복사
COPY common-entity ./common-entity
COPY user ./user

WORKDIR /workspace/user

# 명칭을 이미 user-app.jar로 확정해두었음
RUN chmod +x ./gradlew \
    && ./gradlew clean bootJar --no-daemon \
    && test -f build/libs/user-app.jar \
    && cp build/libs/user-app.jar /tmp/app.jar


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ENV TZ=Asia/Seoul

COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 8080
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]