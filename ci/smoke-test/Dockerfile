# Built by the release workflow against the image tag about to be
# published (see .github/workflows/publish.yml) -- LLMSIM_BUILD_IMAGE is
# supplied via --build-arg in CI, not meant to be built standalone.
ARG LLMSIM_BUILD_IMAGE
FROM ${LLMSIM_BUILD_IMAGE} AS build
COPY TestFlow.scala /build/src/main/scala/example/TestFlow.scala
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
COPY --from=build /build/target/scala-3.3.3/llmsim.jar /app/llmsim.jar
ENV LLMSIM_SCRIPT=example.TestFlow
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/llmsim.jar"]
