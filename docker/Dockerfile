# Build image for the companion app: JDK, Android SDK (platform 36), Gradle for the wrapper.
FROM eclipse-temurin:21-jdk
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/gradle/bin
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools" >/dev/null \
 && chmod -R a+rwX $ANDROID_HOME
RUN curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip \
 && unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-8.14.3 /opt/gradle && rm /tmp/gradle.zip
