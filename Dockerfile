# --- console build stage --------------------------------------------------
# Builds the Tyrian console. Genuinely needs BOTH sbt/JDK (console-tyrian's
# vite.config.js shells out to `sbt consoleTyrian/fullLinkOutputDir`
# internally to locate Scala.js's own compiled output) AND Node/npm --
# this specific Vite setup can't cleanly separate the two the way a
# typical frontend/backend split usually allows.
#
# Kept as its OWN, fully independent stage rather than extending `build`
# below -- `build`'s later instructions (copying all of src/, running its
# own full `sbt assembly`) are irrelevant to building just the console,
# and staying genuinely separate is what keeps Node completely out of
# `build`'s own image layers. `build` is what gets published as
# llmsim-build (see .github/workflows/publish.yml's `target: build`) --
# the base image downstream projects extend via `FROM llmsim-build:
# <version>` + `COPY MyScript.scala` + `sbt assembly`. Those downstream
# builds never touch Scala.js/npm at all, and shouldn't need to just
# because llmsim's own release process does.
#
# Node copied in from the official Node image via multi-stage COPY, not
# apt-get -- jammy's default apt repo Node package would likely be far
# too outdated for a current Vite. Pinned explicitly via NODE_VERSION,
# matching the same compatibility contract declared in
# console-tyrian/package.json's "engines" field, so local dev, Docker,
# and CI all share one explicit version contract rather than three
# independent assumptions.
#
# One thing genuinely not fully verified without an actual build: cross-
# distro binary compatibility, since node-toolchain is Debian bookworm
# and the destination is Ubuntu jammy. Node's official Linux builds are
# typically compiled against an intentionally old glibc baseline
# specifically for broad portability across distros, so this is expected
# to work, not just assumed to -- but it's the one part of this whole
# stage that a real build is needed to actually confirm.
ARG NODE_VERSION=20
FROM node:${NODE_VERSION}-bookworm-slim AS node-toolchain

FROM eclipse-temurin:21-jdk-jammy AS console-tyrian-build
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

COPY --from=node-toolchain /usr/local/ /usr/local/

# Only what consoleTyrian/fullLinkOutputDir actually needs to resolve:
# the build definition, common/ (consoleTyrian depends on common.js), and
# console-tyrian itself. Not root's own src/ -- root is a sibling
# project, not a dependency of consoleTyrian, and sbt doesn't need it
# present just to answer a question about a different project entirely.
COPY project project
COPY build.sbt .
COPY common common
COPY console-tyrian console-tyrian

WORKDIR /build/console-tyrian
RUN npm ci
RUN npm run build

# --- build stage -----------------------------------------------------------
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
# does, not build-definition boilerplate.
COPY common common

# The built console, from the fully independent console-tyrian-build
# stage above -- copied into resources BEFORE assembly runs, so it's
# bundled directly into the jar (matching resourceServiceBuilder's
# classpath-based serving, see App.scala) rather than needing to sit
# alongside the jar on disk. Only these already-built static files cross
# the boundary into this stage -- Node itself never does.
COPY --from=console-tyrian-build /build/console-tyrian/dist src/main/resources/_llmsim/console
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
