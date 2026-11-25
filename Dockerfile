
# ---------- Etapa 1: compilación (JDK) ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Primero el wrapper y los scripts de Gradle para aprovechar la caché de capas
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---------- Etapa 2: ejecución (JRE, usuario no root) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /workspace/build/libs/msa-prospeccion-crm.jar /app/app.jar

USER app

# El puerto lo define SERVER_PORT (por asignar); no se fija aquí.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
