FROM --platform=linux/amd64 docker.io/library/openjdk:17.0.1-jdk-slim
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
EXPOSE 8080
RUN mkdir /app
COPY ./build/libs/app.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
