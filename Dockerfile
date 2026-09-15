FROM eclipse-temurin:17-jdk

WORKDIR /app

RUN mkdir -p /app/lib
RUN curl -L https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar -o /app/lib/sqlite-jdbc.jar

COPY . .

RUN javac -cp "src:lib/sqlite-jdbc.jar" -d out src/Main.java src/WorkoutTrackerApp.java

EXPOSE 8080

ENV WORKOUT_DATA_DIR=/app/data

CMD ["java", "-cp", "out:lib/sqlite-jdbc.jar", "Main"]
