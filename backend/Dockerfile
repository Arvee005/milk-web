# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY backend/pom.xml .
COPY backend/src ./src

# Package, then unpack the jar: Class Data Sharing (below) needs a plain classpath, not nested jars.
RUN mvn clean package -DskipTests \
    && java -Djarmode=tools -jar target/milk-web-backend-0.0.1-SNAPSHOT.jar extract --destination extracted

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/extracted/ ./

# Class Data Sharing training run: start the app once with an in-memory database (dev profile, no migrations),
# record the classes it loads, and stop as soon as the Spring context is ready. Every later start maps this
# archive instead of loading those classes again. Measured on 0.1 CPU / 512 MB (Render free): about 3.5x faster
# startup together with the flags below. See docs/development-log.md (free hosting milestone).
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
    -jar milk-web-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.flyway.enabled=false

EXPOSE 8080

# Small-server settings: the archive above, C1-only compilation (much faster start; logins measured no slower),
# the single-threaded serial collector, and a heap sized to 75% of the container's memory.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", \
            "-XX:MaxRAMPercentage=75", "-Xss512k", "-jar", "milk-web-backend-0.0.1-SNAPSHOT.jar"]
