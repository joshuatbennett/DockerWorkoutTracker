FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN javac src/Main.java src/WorkoutTrackerApp.java

EXPOSE 8080

ENV WORKOUT_DATA_DIR=/app/data

CMD ["java", "-cp", "src", "Main"]
