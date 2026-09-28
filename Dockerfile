# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy pom first for better caching
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources
COPY src ./src

# The agent scripts live at the repo root and are packaged into the jar by
# maven-resources-plugin (execution copy-agent-scripts). Without this COPY they
# are not in the build context: the build still succeeds (an <include> that
# matches nothing is ignored), but the jar comes out without them and the
# console can no longer generate the agent installer.
COPY clamav-agent-poll.sh clamav-onacc-report.sh clamav-telegram-alert.sh install-clamd-remote.sh ./

# Build jar
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN mkdir -p /app/data /app/conf

# Copy jar from build stage
COPY --from=build /src/target/clamav-web-client*.jar /app/clamav-web-client.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/clamav-web-client.jar"]
