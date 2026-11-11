FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -Dmaven.test.skip=true
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r regmac && useradd -r -g regmac regmac && mkdir /documents && chown regmac:regmac /documents
COPY --from=build /src/target/regmac-0.1.0.jar app.jar
USER regmac
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
