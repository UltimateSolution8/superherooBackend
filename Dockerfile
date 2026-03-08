FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the pre-built JAR from the target directory
COPY target/*.jar app.jar

# Tuned JAVA_OPTS for production:
# - G1GC for predictable low latency pauses
# - MaxGCPauseMillis=100 for steady response times
# - MaxRAMPercentage=75.0 to respect container limits without manual sizing
# - UseStringDeduplication for better heap efficiency (JDK 8u20+)
# - ExitOnOutOfMemoryError to let orchestrator (K8s/Docker) restart a sick pod
ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:MaxGCPauseMillis=100 \
               -XX:+UseStringDeduplication \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081

CMD ["sh", "-c", "if [ -n \"$DATABASE_URL\" ] && ! echo \"$DATABASE_URL\" | grep -q '^jdbc:'; then export SPRING_DATASOURCE_URL=\"jdbc:${DATABASE_URL}\"; fi; java $JAVA_OPTS -jar app.jar"]
