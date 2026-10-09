FROM node:24-alpine AS web
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run test:unit && npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS java
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /build/frontend/dist ./src/main/resources/static
RUN mvn -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p data && chown -R 10001:10001 /app
COPY --from=java /build/backend/target/signdesk-0.1.0.jar ./signdesk.jar
USER 10001:10001
ENV SIGNDESK_BIND_ADDRESS=0.0.0.0 SIGNDESK_DATA_DIR=/app/data
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-jar", "/app/signdesk.jar"]
