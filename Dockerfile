FROM mcr.microsoft.com/playwright/java:v1.51.0-noble

# Fetch fallback chain dependencies:
#  - CurlCffiClient / RnetClient shell out to python3 (curl_cffi, rnet TLS tiers)
#  - HeadlessBrowser launches non-headless Chromium under Xvfb on DISPLAY :99
#  - StealthBrowserClient runs real Google Chrome via zendriver under xvfb-run to
#    pass mobile.de's Akamai Bot Manager (real Chrome + undetected CDP + headed display)
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip xvfb wget \
    && wget -qO /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm /tmp/chrome.deb \
    && pip3 install --break-system-packages curl_cffi rnet zendriver "huggingface_hub[hf_xet]" \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Fat jar is built by CI (./gradlew :server:buildFatJar) before docker build.
COPY server/build/libs/server-all.jar /app/server.jar

RUN mkdir -p /var/lib/arbay

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC" \
    ARBAY_DATA_DIR=/var/lib/arbay

EXPOSE 8090
VOLUME /var/lib/arbay

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/server.jar"]
