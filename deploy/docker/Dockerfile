# OAA hub image for docker compose and the Home Assistant app (same image).
# Build context: repository root.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew \
  && OAA_HUB_ONLY=1 ./gradlew :server:jar --no-daemon -x test

FROM eclipse-temurin:17-jre-jammy
RUN mkdir -p /data /app
COPY --from=build /src/server/build/libs/*.jar /app/oaa-hub.jar
COPY deploy/docker/run.sh /run.sh
RUN chmod a+x /run.sh
ENV OAA_DATA=/data
ENV OAA_PORT=8787
ENV OAA_NODE_PORT=8788
EXPOSE 8787 8788
VOLUME ["/data"]
ENTRYPOINT ["/run.sh"]
