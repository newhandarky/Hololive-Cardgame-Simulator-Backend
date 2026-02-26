# 1. Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# 2. Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app

# Render 會把 PORT 環境變數丟進來
ENV PORT=8080
EXPOSE 8080

COPY --from=build /app/target/*.jar app.jar

# 讓 Spring Boot 用 Render 給的 PORT
ENTRYPOINT ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]
