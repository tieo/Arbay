FROM mcr.microsoft.com/playwright/java:v1.51.0-noble

# Fetch fallback chain dependencies:
#  - CurlCffiClient / RnetClient shell out to python3 (curl_cffi, rnet TLS tiers)
#  - HeadlessBrowser launches non-headless Chromium under Xvfb on DISPLAY :99
#  - StealthBrowserClient runs real Google Chrome via zendriver under xvfb-run to
#    pass mobile.de's Akamai Bot Manager (real Chrome + undetected CDP + headed display)
#  - The chat browser signs in with real X input (xdotool), which the login host requires
#  - When a stealth fetch hits a captcha the automation cannot clear, x11vnc + websockify expose
#    that live Chrome session over noVNC so the user can solve it in the crawler's own browser
#    (same IP + fingerprint the token binds to); novnc ships the static web client.
#  - SmartScreens reads the SMART screens in a drive ad's photos with RapidOCR (PaddleOCR's models on
#    ONNX Runtime; its OpenCV wants libGL and glib), which reads a phone photo of a monitor where
#    Tesseract does not (installed beside Debian's numpy, which pip cannot replace); ModelGuesses reads type plates with Tesseract (German and English).
# Chrome comes in through ADD so every build fetches the current stable: a layer cached with an
# older Chrome kept 150 in the image, which mobile.de answered "Access denied" while 155 got results.
ADD https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb /tmp/chrome.deb
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip xvfb x11vnc websockify novnc xdotool tesseract-ocr tesseract-ocr-deu libgl1 libglib2.0-0 \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm /tmp/chrome.deb \
    && pip3 install --break-system-packages curl_cffi rnet zendriver "huggingface_hub[hf_xet]" \
    && pip3 install --break-system-packages --ignore-installed rapidocr_onnxruntime==1.4.4 \
    && rm -rf /var/lib/apt/lists/*

# OfferJudge asks Claude what each offer is, through the Claude Code CLI signed in with the
# account token the container gets as CLAUDE_CODE_OAUTH_TOKEN. Installed fresh on every full build;
# the copy in /usr/local/bin cannot update itself, so the updater stays off.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://claude.ai/install.sh | bash \
    && cp -L /root/.local/bin/claude /usr/local/bin/claude && rm -rf /root/.local/share/claude /root/.local/bin/claude
ENV DISABLE_AUTOUPDATER=1

WORKDIR /app

# Fat jar is built by CI (./gradlew :server:buildFatJar) before docker build.
COPY server/build/libs/server-all.jar /app/server.jar

RUN mkdir -p /var/lib/arbay

# The server keeps its data in ~/.arbay (see DataDir.kt), so user.home is the volume.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.home=/var/lib/arbay"

EXPOSE 8090
VOLUME /var/lib/arbay

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/server.jar"]
