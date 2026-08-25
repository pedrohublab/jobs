FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# Run go-offline to cache dependencies (optional but speeds up builds)
RUN ./mvnw dependency:go-offline || true
COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080 5005
ENV JAVA_OPTS=""
RUN addgroup -S app && adduser -S app -G app
USER app
ENTRYPOINT [ "sh", "-c", "exec java $JAVA_OPTS -jar app.jar" ]
