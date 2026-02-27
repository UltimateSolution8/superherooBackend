FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the pre-built JAR from the target directory
COPY target/*.jar app.jar

ENV JAVA_OPTS=""
EXPOSE 8081

CMD ["sh", "-c", "if [ -n \"$DATABASE_URL\" ] && ! echo \"$DATABASE_URL\" | grep -q '^jdbc:'; then export SPRING_DATASOURCE_URL=\"jdbc:${DATABASE_URL}\"; fi; java $JAVA_OPTS -jar app.jar"]
