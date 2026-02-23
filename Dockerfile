FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

# Limit Maven memory to prevent OOM during build on Render and use batch mode
ENV MAVEN_OPTS="-Xmx512m"
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/api-0.1.0.jar ./app.jar

ENV JAVA_OPTS=""
EXPOSE 8080

CMD ["sh", "-c", "if [ -n \"$DATABASE_URL\" ] && ! echo \"$DATABASE_URL\" | grep -q '^jdbc:'; then export SPRING_DATASOURCE_URL=\"jdbc:${DATABASE_URL}\"; fi; java $JAVA_OPTS -jar app.jar"]
