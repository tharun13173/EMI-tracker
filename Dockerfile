FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src src
RUN mkdir out && javac -d out src/branch/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out out
COPY lib lib
COPY web web
ENV PORT=8080 BRANCH_DB=/data/branch.db
VOLUME /data
EXPOSE 8080
CMD ["java", "-cp", "out:lib/sqlite-jdbc.jar", "branch.Main"]
