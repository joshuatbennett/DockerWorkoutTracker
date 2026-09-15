# Workout Tracker

A simple Java workout tracker that helps you:

- see the current day's workout plan
- display target weight, sets, and reps for each exercise
- log completed workouts from a browser
- use previous workout history to automatically suggest the next target weight

## Run it on your home server

From the project root:

```bash
javac src\Main.java src\WorkoutTrackerApp.java
java -cp src Main
```

Then open this in a browser on the same machine or any device on your home network:

```text
http://<server-ip>:8080
```

Example:

```text
http://192.168.1.25:8080
```

## Run it with Docker

Build the image:

```bash
docker build -t workout-tracker .
```

Run the container:

```bash
docker run --rm -p 8080:8080 -v "$(pwd)/data:/app/data" workout-tracker
```

This keeps the SQLite database in a local `data` folder on the host so it persists between container restarts.

The app stores workout history in `workout.db` inside the mounted data directory, rather than a plain text file.

## Portainer / Docker stack

The stack keeps the database in a named Docker volume, so your history survives rebuilds/restarts without losing data.

```yaml
version: "3.9"
services:
  workout-tracker:
    image: workout-tracker:latest
    container_name: workout-tracker
    restart: unless-stopped
    environment:
      WORKOUT_DATA_DIR: /app/data
    ports:
      - "8080:8080"
    volumes:
      - workout-tracker-data:/app/data
volumes:
  workout-tracker-data:
    driver: local
```

## Features

- 4-day Upper/Lower split based on the home-gym strength program in `src/resources/Home_Gym_Upper_Lower_Program.md`
- Double-progression tracking with rep ranges and weight increases after reaching the top of the range
- Persistent SQLite database storage in `workout.db`
- Browser-based dashboard for tracking workouts over the local network
