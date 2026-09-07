# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy pom first for better caching
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources
COPY src ./src

# Gli script dell'agent stanno nella root del repo e vengono impacchettati nel
# jar da maven-resources-plugin (esecuzione copy-agent-scripts). Senza questa
# COPY non entrano nel build context: il build riesce lo stesso (gli <include>
# mancanti vengono ignorati) ma il jar esce senza, e la console non riesce piu'
# a generare l'installer dell'agent.
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
