# Build context = a própria pasta do inspecta-messenger. inspecta-core é resolvido via Maven
# a partir do Reposilite (repositório interno no Dokploy, ver pom.xml <repositories>)
# — não precisa mais clonar o repo inspecta-core lado a lado.
#   docker build -f Dockerfile .
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /ws
COPY settings.xml settings.xml
COPY pom.xml pom.xml
COPY src src
# inspecta-core e 1.3.0-SNAPSHOT (mutavel). Sem este ARG o Docker reusa a layer do
# `mvn package` sempre que pom.xml/src nao mudam e assa o jar do inspecta-core do
# build anterior, mesmo com updatePolicy=always (a layer nao re-roda, entao o
# download nem acontece). ci/entrypoint.sh passa o <lastUpdated> do maven-metadata
# do SNAPSHOT; muda a cada deploy do inspecta-core. Default `dev` = build local ok.
ARG GU_SNAPSHOT_TS=dev
RUN echo "inspecta-core snapshot: ${GU_SNAPSHOT_TS}" \
 && mvn -q -s settings.xml -f pom.xml package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /ws/target/*.jar app.jar
EXPOSE 4100
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:4100/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
