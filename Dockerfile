# Imagen de ejecución: se construye a partir del JAR generado con ./mvnw package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY target/ms-pedidos360-rabbit-admin-*.jar app.jar
USER app
EXPOSE 8083
ENV JAVA_OPTS="-Xms128m -Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
