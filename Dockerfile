FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY . .
RUN javac AgarServer.java
CMD ["java", "AgarServer"]
