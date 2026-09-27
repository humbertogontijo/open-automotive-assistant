# syntax=docker/dockerfile:1
# OAA hub image for docker compose and the Home Assistant app (same image).
# Build context: repository root.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY . .
# Cache mounts keep Gradle dependencies, the Node.js download and npm packages between builds.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.npm \
    --mount=type=cache,target=/src/features/web/ui/.gradle \
    chmod +x gradlew \
  && OAA_HUB_ONLY=1 ./gradlew :server:jar --no-daemon
# Home Assistant app helpers (run.sh): its public URL for OAA_PUBLIC_NODE_URL, and notifications.
RUN javac -cp "$(ls server/build/libs/*.jar)" -d /src/addon deploy/homeassistant/addon/*.java

FROM eclipse-temurin:17-jre-jammy
RUN mkdir -p /data /app
COPY --from=build /src/server/build/libs/*.jar /app/oaa-hub.jar
COPY --from=build /src/addon /app/addon
# The Home Assistant app copies this into Home Assistant's custom_components (run.sh).
COPY homeassistant/custom_components/open_automotive_assistant /app/integration/open_automotive_assistant
COPY deploy/docker/run.sh /run.sh
RUN chmod a+x /run.sh
ENV OAA_DATA=/data
ENV OAA_PORT=8787
ENV OAA_NODE_PORT=8788
EXPOSE 8787 8788
VOLUME ["/data"]
ENTRYPOINT ["/run.sh"]
