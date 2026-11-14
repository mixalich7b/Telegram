FROM ubuntu:24.04

ARG RUNNER_VERSION=2.337.0
ARG ANDROID_CMDLINE_TOOLS=15859902
ARG ANDROID_PLATFORM=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=27.2.12479018
ARG CMAKE=3.22.1
ARG GO_VERSION=1.24.4

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"
ENV PATH="/usr/local/go/bin:${PATH}"
ENV GOTOOLCHAIN=local

RUN apt-get update && apt-get install -y \
    build-essential \
    ca-certificates \
    curl \
    git \
    unzip \
    zip \
    jq \
    openjdk-17-jdk \
    libicu74 \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Go for host tests and Android cgo builds (the runner image targets linux/x64).
RUN curl -fsSL "https://dl.google.com/go/go${GO_VERSION}.linux-amd64.tar.gz" \
      -o /tmp/go.tar.gz && \
    tar -C /usr/local -xzf /tmp/go.tar.gz && \
    rm /tmp/go.tar.gz && \
    go version

#
# Android SDK
#

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip" \
      -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/android-tools && \
    mv /tmp/android-tools/cmdline-tools \
       ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager \
      "platform-tools" \
      "platforms;android-${ANDROID_PLATFORM}" \
      "build-tools;35.0.0" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${ANDROID_NDK}" \
      "cmake;${CMAKE}"

#
# GitHub Actions runner
#

RUN mkdir -p /opt/actions-runner && \
    curl -fsSL \
      "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" \
      | tar xz -C /opt/actions-runner

RUN /opt/actions-runner/bin/installdependencies.sh

COPY entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

RUN useradd \
    --create-home \
    --uid 1001 \
    --shell /bin/bash \
    runner

RUN mkdir -p \
    /runner \
    /ci/repo \
    /ci/output \
    /home/runner/.gradle \
    && chown -R runner:runner \
       /runner \
       /ci \
       /home/runner

RUN chown -R runner:runner /opt/android-sdk

ENV HOME=/home/runner
ENV GRADLE_USER_HOME=/home/runner/.gradle

USER runner
WORKDIR /runner

ENTRYPOINT ["/entrypoint.sh"]
