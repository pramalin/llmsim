# --- build stage ---------------------------------------------------------
# eclipse-temurin:21-jdk-jammy is Docker's official Temurin image (Ubuntu
# 22.04 "jammy" base) -- sbt itself isn't published as part of it, so it's
# installed here via the apt repository documented at
# https://www.scala-sbt.org/download/
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

RUN apt-get update \
 && apt-get install -y --no-install-recommends apt-transport-https curl gnupg \
 && mkdir -p /usr/share/keyrings \
 && curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
      | gpg --dearmor -o /usr/share/keyrings/scalasbt.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/scalasbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
      > /etc/apt/sources.list.d/sbt.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends sbt \
 && rm -rf /var/lib/apt/lists/*

# Copy just the build definition first so this layer (dependency
# resolution) is cached across rebuilds that only touch src/.
COPY project project
COPY build.sbt .
RUN sbt update

COPY src src
# common/ holds types root now depends on (common.jvm) since the
# console reorganization -- copied here, not with project/build.sbt
# above, since it's real source that changes about as often as src/
# does, not build-definition boilerplate. Only common's source is
# needed here, not console-tyrian's -- root depends on common.jvm
# only, never touches Scala.js at all, so nothing here needs Node/npm.
COPY common common
RUN sbt assembly

# --- test stage --------------------------------------------------------
# `docker build --target test .` runs the full test suite as a CI gate,
# with no need for a separately provisioned JDK/sbt on the CI runner --
# reuses this same build stage's warm dependency cache.
FROM build AS test
RUN sbt test

# --- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/scala-3.3.3/llmsim.jar /app/llmsim.jar

ENV LLMSIM_SCRIPT=com.alai.llmsim.scripts.Default
EXPOSE 8089

ENTRYPOINT ["java", "-jar", "/app/llmsim.jar"]
