# Build environment for the Android app, matching Source/Android/app/build.gradle.kts.
FROM harbor.cruxial.org/cache/library/eclipse-temurin:21-jdk-noble

ARG CMDLINE_TOOLS=16111833
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:$PATH

RUN apt-get update && \
    apt-get install -y --no-install-recommends ccache curl git jq unzip && \
    rm -rf /var/lib/apt/lists/*

RUN curl -fsSLo /tmp/tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" && \
    mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" && \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" && \
    rm /tmp/tools.zip && \
    yes | sdkmanager --licenses > /dev/null && \
    sdkmanager --install \
      "platforms;android-37.0" \
      "build-tools;36.0.0" \
      "build-tools;37.0.0" \
      "platform-tools" \
      "cmake;3.31.6" \
      "ndk;30.0.15729638" > /dev/null

COPY ci.sh abi.init.gradle /ci/
ENTRYPOINT ["/ci/ci.sh"]
