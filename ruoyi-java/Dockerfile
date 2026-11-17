FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -pl ruoyi-admin -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/ruoyi-admin/target/ruoyi-admin.jar /app/ruoyi-admin.jar
EXPOSE 80
ENTRYPOINT ["java", "-jar", "/app/ruoyi-admin.jar"]
