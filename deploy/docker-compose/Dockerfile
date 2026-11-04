# The image of one service (README sections 20 and 23).
#
# One Dockerfile for all six, because they differ in exactly one thing: which module's jar
# ends up in it. Six nearly identical files would be six places to forget a base image
# upgrade, and a service whose image was built differently from its neighbours would be a
# difference nobody chose.
#
# The build stage takes no argument, which is what makes this cheap. Docker caches it after
# the first image, so the reactor is built once and the five images after it are an unpack
# and a copy; the service is named only in the stages below, where a different value costs
# nothing already built.
#
# Built from the repository root, because a service depends on libs/ and on the parent POM:
#   docker build --file deploy/docker-compose/Dockerfile --build-arg SERVICE=gateway .
# which is what the build blocks of docker-compose.yml say.

# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper, so the image builds with the Maven this repository pins rather than whatever
# a base image happens to carry.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY libs/ libs/
COPY services/ services/

# A cache mount rather than the usual trick of copying every POM first and resolving
# dependencies before the sources: the local repository survives between builds either way,
# and this keeps the file honest about what it does. Tests are skipped because they have run
# already: the build of README section 23 gates a commit, and an image is made of code that
# passed it. Running them here instead would need a Postgres, a Redis and a broker inside the
# builder.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -DskipTests -DskipITs package

# ---------------------------------------------------------------------------------------
# Unpacking the jar is a stage of its own, so the layers below can be copied one at a time.
FROM build AS unpack

ARG SERVICE
WORKDIR /src

# The dependencies and the application come out as separate directories, which is the whole
# point: 60 MB of libraries that change when a dependency does, and 40 KB of classes that
# change on every commit. A single fat jar would be one 60 MB layer rebuilt for a one line
# fix, six times over.
RUN java -Djarmode=tools -jar services/${SERVICE}/target/*-boot.jar \
    extract --destination /app --application-filename app.jar

# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# curl is here for the health check of docker-compose.yml, and that check is what make up
# waits on: without it, every service would be reported as started the moment its process
# existed, and the first request of a demo would race Flyway.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Not root. Nothing in here needs to write outside /tmp, and the one thing a demo stack
# should not teach is that a service runs as root because it was easier.
RUN useradd --create-home --uid 10001 seatrush
USER seatrush
WORKDIR /app

# Oldest layer first, newest last, which is the order they change in.
COPY --from=unpack --chown=seatrush:seatrush /app/lib/ lib/
COPY --from=unpack --chown=seatrush:seatrush /app/app.jar ./

# The port each service answers on is its own (README section 23) and is set by compose;
# this is only what the image says about itself.
EXPOSE 8080

# A share of the container limit rather than a fixed heap, so the same image behaves in a
# compose stack and under a Kubernetes limit without being told which it is in.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Through the shell, so JAVA_OPTS is split into arguments; exec, so the JVM is PID 1 and
# gets the stop signal rather than a shell that would have to pass it on.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
