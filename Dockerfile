# ---------- Stage 1: build ----------
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies get their own layer, so they are only re-downloaded when pom.xml changes.
# ("|| true": if this optional warm-up ever fails, the real build below still works.)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Unpack the fat jar into a plain folder (app jar + lib/). This starts faster than a
# nested fat jar and is required for the class-data-sharing archive built below.
RUN java -Djarmode=tools -jar target/backend-1.0.0.jar extract --destination /app/extracted

# ---------- Stage 2: run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/extracted/ /app/

# Tuned for Render free tier (512 MB RAM, shared CPU):
#  MaxRAMPercentage=60     heap ~300 MB; the rest is left for metaspace, threads, gRPC/Netty
#  UseSerialGC             lowest memory, no extra GC threads
#  TieredStopAtLevel=1     C1 compiler only: faster startup, less CPU/memory
#  Xss512k                 smaller thread stacks
#  ExitOnOutOfMemoryError  let Render restart a broken JVM instead of leaving it hung
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8"

# BEST-EFFORT startup accelerator (Class Data Sharing archive).
# Runs the app once at build time with dummy settings and no reachable database, so it
# stops early, but every class it loaded up to that point is saved to app.jsa and reused
# on each real start. Nothing real is used here: no secrets, no DB writes.
# If this step fails or times out, the image still builds and the app starts normally.
RUN env DB_URL="jdbc:mysql://127.0.0.1:1/none" DB_USERNAME=x DB_PASSWORD=x \
        JWT_SECRET="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==" \
        KYC_ENCRYPTION_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
        VAULT_SECRET="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==" \
        LINKEDIN_ACCESS_TOKEN=x FIREBASE_PROJECT_ID=x FIREBASE_PRIVATE_KEY=x FIREBASE_PRIVATE_KEY_ID=x \
        FIREBASE_CLIENT_EMAIL=x FIREBASE_CLIENT_ID=x FIREBASE_DATABASE_URL=x \
        timeout 120 java $JAVA_OPTS \
        -XX:ArchiveClassesAtExit=/app/app.jsa \
        -Dspring.context.exit=onRefresh \
        -jar /app/backend-1.0.0.jar > /tmp/cds-training.log 2>&1 || true
RUN ls -la /app/app.jsa || echo "CDS archive was not created - app will still start normally"

# Run as an unprivileged user instead of root (limits the damage if the app is ever compromised).
# /app must be writable because the app stores generated images under /app/uploads.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/uploads && chown -R app:app /app
USER app

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=/app/app.jsa -Xshare:auto -jar /app/backend-1.0.0.jar"]