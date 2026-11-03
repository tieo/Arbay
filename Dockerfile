FROM eclipse-temurin:21-jre-noble

# Every page is read and every market account worked through Neuland (NEULAND_URL); what the server
# still runs itself:
#  - SmartScreens reads the SMART screens in a drive ad's photos with RapidOCR (PaddleOCR's models on
#    ONNX Runtime; its OpenCV wants libGL and glib), which reads a phone photo of a monitor where
#    Tesseract does not (installed beside Debian's numpy, which pip cannot replace); ModelGuesses reads type plates with Tesseract (German and English).
#  - pip installs beside Debian's own Python packages (--ignore-installed): it cannot uninstall one
#    Debian installed (typing_extensions), and their newer releases need newer ones.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip tesseract-ocr tesseract-ocr-deu libgl1 libglib2.0-0 \
    && pip3 install --break-system-packages --ignore-installed "huggingface_hub[hf_xet]" \
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
