FROM mcr.microsoft.com/playwright/java:v1.51.0-noble

WORKDIR /app

# Fat jar is built by CI (./gradlew :server:buildFatJar) before docker build.
COPY server/build/libs/server-all.jar /app/server.jar

RUN mkdir -p /var/lib/arbay

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC" \
    ARBAY_DATA_DIR=/var/lib/arbay

EXPOSE 8090
VOLUME /var/lib/arbay

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/server.jar"]
