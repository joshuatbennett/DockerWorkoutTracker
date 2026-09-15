import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class WorkoutTrackerApp {
    private static final int PORT = 8080;
    private static final int DELoadCycleDays = 35;
    private static final Path WORKOUT_DATA_DIR = Paths.get(System.getenv().getOrDefault("WORKOUT_DATA_DIR", "."));
    private static final Path DATABASE_PATH = WORKOUT_DATA_DIR.resolve("workout.db");
    private static final Path LEGACY_DATA_FILE = WORKOUT_DATA_DIR.resolve("workout_history.txt");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final List<WorkoutEntry> workouts = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        WorkoutTrackerApp app = new WorkoutTrackerApp();
        app.loadWorkouts();
        app.startServer();
    }

    private void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", this::serveRootPage);
        server.createContext("/api/workout-plan", this::serveWorkoutPlan);
        server.createContext("/api/log-workout", this::saveWorkoutLog);
        server.createContext("/api/history", this::serveHistory);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Workout tracker server is running.");
        System.out.println("Open http://localhost:" + PORT + " in a browser on this machine.");
        System.out.println("From another PC on your home network, use http://<server-ip>:" + PORT);
    }

    private void serveRootPage(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "text/html; charset=UTF-8", buildHtmlPage());
    }

    private void serveWorkoutPlan(HttpExchange exchange) throws IOException {
        LocalDate today = LocalDate.now();
        String selectedRoutine = resolveRoutineFromQuery(exchange);
        List<ExerciseTemplate> templates = getExercisesForRoutine(selectedRoutine);
        StringBuilder json = new StringBuilder();
        json.append("{\"date\":\"").append(today.format(FILE_DATE_FORMAT)).append("\",");
        json.append("\"day\":\"").append(formatDayName(today.getDayOfWeek())).append("\",");
        json.append("\"routine\":\"").append(selectedRoutine).append("\",");
        json.append("\"deload\":").append(shouldApplyScheduledDeload() ? "true" : "false").append(",");
        json.append("\"exercises\":[");

        for (int i = 0; i < templates.size(); i++) {
            ExerciseTemplate template = templates.get(i);
            ProgressionPlan plan = recommendPlan(template);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"name\":\"").append(template.name).append("\",");
            json.append("\"sets\":").append(template.sets).append(",");
            json.append("\"repFloor\":").append(template.repFloor).append(",");
            json.append("\"repCeiling\":").append(template.repCeiling).append(",");
            json.append("\"targetWeight\":").append(plan.targetWeight).append(",");
            json.append("\"lastWeight\":").append(plan.lastWeight).append(",");
            json.append("\"lastMaxReps\":").append(plan.lastMaxReps).append(",");
            json.append("\"deload\":").append(plan.deload ? "true" : "false").append("}");
        }
        json.append("]}");

        sendResponse(exchange, 200, "application/json; charset=UTF-8", json.toString());
    }

    private String resolveRoutineFromQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return getRoutineForDay(LocalDate.now().getDayOfWeek());
        }

        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && "routine".equals(pair[0])) {
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }

        return getRoutineForDay(LocalDate.now().getDayOfWeek());
    }

    private String getRoutineForDay(DayOfWeek day) {
        if (day == DayOfWeek.MONDAY) {
            return "Upper 1";
        }
        if (day == DayOfWeek.TUESDAY) {
            return "Lower 1";
        }
        if (day == DayOfWeek.THURSDAY) {
            return "Upper 2";
        }
        if (day == DayOfWeek.FRIDAY) {
            return "Lower 2";
        }
        return "Upper 1";
    }

    private void saveWorkoutLog(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json; charset=UTF-8", "{\"success\":false,\"message\":\"Method not allowed\"}");
            return;
        }

        String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String selectedRoutine = resolveRoutineFromFormBody(rawBody);
        List<ExerciseLog> logs = parseWorkoutBody(rawBody);
        if (logs.isEmpty()) {
            sendResponse(exchange, 400, "application/json; charset=UTF-8", "{\"success\":false,\"message\":\"No workout data was received\"}");
            return;
        }

        LocalDate today = LocalDate.now();
        String entryDayName = selectedRoutine != null && !selectedRoutine.isBlank() ? selectedRoutine : today.getDayOfWeek().name();
        WorkoutEntry entry = new WorkoutEntry(today, entryDayName);
        entry.exercises.addAll(logs);
        workouts.add(entry);
        saveWorkouts();

        sendResponse(exchange, 200, "application/json; charset=UTF-8", "{\"success\":true,\"message\":\"Workout saved\"}");
    }

    private String resolveRoutineFromFormBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            String decoded = URLDecoder.decode(rawBody, StandardCharsets.UTF_8);
            for (String pair : decoded.split("&")) {
                String[] values = pair.split("=", 2);
                if (values.length == 2 && "routine".equals(values[0])) {
                    String value = values[1].replace('+', ' ').trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private void serveHistory(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("[");
        for (int i = 0; i < workouts.size(); i++) {
            WorkoutEntry entry = workouts.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"date\":\"").append(entry.date.format(FILE_DATE_FORMAT)).append("\",\"day\":\"").append(entry.dayName).append("\",\"exercises\":[");
            for (int j = 0; j < entry.exercises.size(); j++) {
                ExerciseLog log = entry.exercises.get(j);
                if (j > 0) {
                    json.append(",");
                }
                json.append("{\"name\":\"").append(log.exerciseName).append("\",\"reps\":[");
                for (int k = 0; k < log.repsBySet.size(); k++) {
                    if (k > 0) {
                        json.append(",");
                    }
                    json.append(log.repsBySet.get(k));
                }
                json.append("]}");
            }
            json.append("]}");
        }
        json.append("]");
        sendResponse(exchange, 200, "application/json; charset=UTF-8", json.toString());
    }

    private List<ExerciseLog> parseWorkoutBody(String rawBody) {
        List<ExerciseLog> logs = new ArrayList<>();
        if (rawBody == null || rawBody.isBlank()) {
            return logs;
        }

        try {
            String decoded = URLDecoder.decode(rawBody, StandardCharsets.UTF_8);
            String[] pairs = decoded.split("&");
            for (String pair : pairs) {
                String[] values = pair.split("=", 2);
                if (values.length != 2 || values[0].isBlank()) {
                    continue;
                }
                if ("routine".equals(values[0])) {
                    continue;
                }

                String exerciseName = values[0].replace("+", " ");
                String[] repValues = values[1].split("\\|");
                List<Integer> reps = new ArrayList<>();
                for (String repValue : repValues) {
                    String trimmed = repValue.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    reps.add(Integer.parseInt(trimmed));
                }
                if (!reps.isEmpty()) {
                    logs.add(new ExerciseLog(exerciseName, reps));
                }
            }
        } catch (Exception ignored) {
            return logs;
        }

        return logs;
    }

    private void loadWorkouts() {
        initializeDatabase();
        workouts.clear();

        try (Connection connection = getDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, entry_date, day_name FROM workout_sessions ORDER BY entry_date ASC, id ASC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                LocalDate date = LocalDate.parse(resultSet.getString("entry_date"), FILE_DATE_FORMAT);
                WorkoutEntry entry = new WorkoutEntry(date, resultSet.getString("day_name"));

                try (PreparedStatement exerciseStatement = connection.prepareStatement(
                        "SELECT exercise_name, reps_csv FROM exercise_logs WHERE session_id = ? ORDER BY id ASC")) {
                    exerciseStatement.setInt(1, resultSet.getInt("id"));
                    try (ResultSet exerciseSet = exerciseStatement.executeQuery()) {
                        while (exerciseSet.next()) {
                            String exerciseName = exerciseSet.getString("exercise_name");
                            String repsCsv = exerciseSet.getString("reps_csv");
                            List<Integer> repsBySet = new ArrayList<>();
                            if (repsCsv != null && !repsCsv.isBlank()) {
                                String[] repValues = repsCsv.split("\\|");
                                for (String repValue : repValues) {
                                    String trimmed = repValue.trim();
                                    if (!trimmed.isBlank()) {
                                        repsBySet.add(Integer.parseInt(trimmed));
                                    }
                                }
                            }
                            if (!repsBySet.isEmpty()) {
                                entry.exercises.add(new ExerciseLog(exerciseName, repsBySet));
                            }
                        }
                    }
                }
                workouts.add(entry);
            }
        } catch (SQLException e) {
            System.out.println("Could not read workout database: " + e.getMessage());
        }

        if (workouts.isEmpty() && Files.exists(LEGACY_DATA_FILE)) {
            loadLegacyWorkouts();
            saveWorkouts();
        }
    }

    private void initializeDatabase() {
        Path parent = DATABASE_PATH.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                System.out.println("Could not create workout data directory: " + e.getMessage());
                return;
            }
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.out.println("Could not load SQLite JDBC driver: " + e.getMessage());
            return;
        }

        try (Connection connection = getDatabaseConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS workout_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_date TEXT NOT NULL, day_name TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exercise_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, exercise_name TEXT NOT NULL, reps_csv TEXT NOT NULL, FOREIGN KEY(session_id) REFERENCES workout_sessions(id))");
        } catch (SQLException e) {
            System.out.println("Could not create workout database: " + e.getMessage());
        }
    }

    private void loadLegacyWorkouts() {
        try {
            List<String> lines = Files.readAllLines(LEGACY_DATA_FILE);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] columns = line.split("\\|");
                if (columns.length < 3) {
                    continue;
                }

                LocalDate date = LocalDate.parse(columns[0], FILE_DATE_FORMAT);
                String dayName = columns[1];
                WorkoutEntry entry = new WorkoutEntry(date, dayName);

                for (int i = 2; i < columns.length; i++) {
                    String[] parts = columns[i].split("~");
                    if (parts.length < 2) {
                        continue;
                    }

                    String exerciseName = parts[0];
                    List<Integer> repsBySet = new ArrayList<>();
                    for (int j = 1; j < parts.length; j++) {
                        String[] repValues = parts[j].split("\\|");
                        for (String repValue : repValues) {
                            if (!repValue.isBlank()) {
                                repsBySet.add(Integer.parseInt(repValue));
                            }
                        }
                    }
                    if (!repsBySet.isEmpty()) {
                        entry.exercises.add(new ExerciseLog(exerciseName, repsBySet));
                    }
                }

                workouts.add(entry);
            }
        } catch (IOException e) {
            System.out.println("Could not read legacy workout file: " + e.getMessage());
        }
    }

    private void saveWorkouts() {
        initializeDatabase();
        try (Connection connection = getDatabaseConnection()) {
            connection.setAutoCommit(false);
            try (Statement resetStatement = connection.createStatement()) {
                resetStatement.executeUpdate("DELETE FROM exercise_logs");
                resetStatement.executeUpdate("DELETE FROM workout_sessions");
            }

            try (PreparedStatement sessionStatement = connection.prepareStatement(
                    "INSERT INTO workout_sessions (entry_date, day_name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement exerciseStatement = connection.prepareStatement(
                    "INSERT INTO exercise_logs (session_id, exercise_name, reps_csv) VALUES (?, ?, ?)")) {

                for (WorkoutEntry entry : workouts) {
                    sessionStatement.setString(1, entry.date.format(FILE_DATE_FORMAT));
                    sessionStatement.setString(2, entry.dayName);
                    sessionStatement.executeUpdate();

                    try (ResultSet keys = sessionStatement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            continue;
                        }
                        int sessionId = keys.getInt(1);
                        for (ExerciseLog log : entry.exercises) {
                            StringBuilder repsBuilder = new StringBuilder();
                            for (int i = 0; i < log.repsBySet.size(); i++) {
                                if (i > 0) {
                                    repsBuilder.append('|');
                                }
                                repsBuilder.append(log.repsBySet.get(i));
                            }
                            exerciseStatement.setInt(1, sessionId);
                            exerciseStatement.setString(2, log.exerciseName);
                            exerciseStatement.setString(3, repsBuilder.toString());
                            exerciseStatement.addBatch();
                        }
                    }
                }
                exerciseStatement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            System.out.println("Could not save workout database: " + e.getMessage());
        }
    }

    private Connection getDatabaseConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
    }

    private ProgressionPlan recommendPlan(ExerciseTemplate template) {
        ExerciseLog last = findMostRecentLog(template.name);
        if (last == null) {
            return new ProgressionPlan(template.baseWeight, 0.0, 0, false);
        }

        if (shouldApplyScheduledDeload()) {
            double deloadWeight = roundToHalf(template.baseWeight * 0.6);
            return new ProgressionPlan(deloadWeight, template.baseWeight, maxReps(last.repsBySet), true);
        }

        if (isStallPattern(template, last)) {
            double reducedWeight = roundToHalf(template.baseWeight * 0.9);
            return new ProgressionPlan(reducedWeight, template.baseWeight, maxReps(last.repsBySet), false);
        }

        double currentWeight = template.baseWeight;
        if (last.repsBySet.size() >= template.sets && allRepsAtOrAboveTarget(last.repsBySet, template.repFloor)) {
            currentWeight = roundToHalf(currentWeight + template.incrementLb);
        }

        return new ProgressionPlan(currentWeight, template.baseWeight, maxReps(last.repsBySet), false);
    }

    private boolean shouldApplyScheduledDeload() {
        if (workouts.size() < 4) {
            return false;
        }

        LocalDate firstWorkout = workouts.get(0).date;
        LocalDate mostRecentWorkout = workouts.get(workouts.size() - 1).date;
        long daysSinceCycleStart = ChronoUnit.DAYS.between(firstWorkout, mostRecentWorkout);
        return daysSinceCycleStart >= DELoadCycleDays && (daysSinceCycleStart % DELoadCycleDays) < 7;
    }

    private boolean isStallPattern(ExerciseTemplate template, ExerciseLog lastLog) {
        if (lastLog == null || lastLog.repsBySet.isEmpty()) {
            return false;
        }

        List<ExerciseLog> recentLogs = findRecentLogsForExercise(template.name, 3);
        if (recentLogs.size() < 2) {
            return false;
        }

        int missedTargetCount = 0;
        for (ExerciseLog log : recentLogs) {
            if (log == null || log.repsBySet.isEmpty()) {
                continue;
            }
            if (hasAnyRepBelowTarget(log.repsBySet, template.repFloor)) {
                missedTargetCount++;
            }
        }
        return missedTargetCount >= 2;
    }

    private List<ExerciseLog> findRecentLogsForExercise(String exerciseName, int limit) {
        List<ExerciseLog> matches = new ArrayList<>();
        for (int i = workouts.size() - 1; i >= 0 && matches.size() < limit; i--) {
            WorkoutEntry entry = workouts.get(i);
            for (int j = entry.exercises.size() - 1; j >= 0 && matches.size() < limit; j--) {
                ExerciseLog log = entry.exercises.get(j);
                if (log.exerciseName.equalsIgnoreCase(exerciseName)) {
                    matches.add(log);
                }
            }
        }
        return matches;
    }

    private boolean allRepsAtOrAboveTarget(List<Integer> repsBySet, int targetReps) {
        if (repsBySet.isEmpty()) {
            return false;
        }
        for (int reps : repsBySet) {
            if (reps < targetReps) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAnyRepBelowTarget(List<Integer> repsBySet, int targetReps) {
        for (int reps : repsBySet) {
            if (reps < targetReps) {
                return true;
            }
        }
        return false;
    }

    private int maxReps(List<Integer> repsBySet) {
        int max = 0;
        for (int reps : repsBySet) {
            if (reps > max) {
                max = reps;
            }
        }
        return max;
    }

    private ExerciseLog findMostRecentLog(String exerciseName) {
        for (int i = workouts.size() - 1; i >= 0; i--) {
            WorkoutEntry entry = workouts.get(i);
            for (int j = entry.exercises.size() - 1; j >= 0; j--) {
                ExerciseLog log = entry.exercises.get(j);
                if (log.exerciseName.equalsIgnoreCase(exerciseName)) {
                    return log;
                }
            }
        }
        return null;
    }

    private List<ExerciseTemplate> getExercisesForDay(DayOfWeek dayOfWeek) {
        String routine = getRoutineForDay(dayOfWeek);
        return getExercisesForRoutine(routine);
    }

    private List<ExerciseTemplate> getExercisesForRoutine(String routineName) {
        switch (routineName) {
            case "Upper 1":
                return new ArrayList<>(UPPER_1_PLAN);
            case "Upper 2":
                return new ArrayList<>(UPPER_2_PLAN);
            case "Lower 1":
                return new ArrayList<>(LOWER_1_PLAN);
            case "Lower 2":
                return new ArrayList<>(LOWER_2_PLAN);
            default:
                return new ArrayList<>(UPPER_1_PLAN);
        }
    }

    private static String formatDayName(DayOfWeek day) {
        String value = day.name();
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
    }

    private static double roundToHalf(double value) {
        return Math.round(value * 2.0) / 2.0;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static final List<ExerciseTemplate> UPPER_1_PLAN = List.of(
            new ExerciseTemplate("Barbell Bench Press", 4, 5, 5, 5.0, 115.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Barbell Bent-Over Row", 4, 5, 5, 5.0, 125.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Landmine Press", 3, 6, 8, 5.0, 45.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Lat Pulldown", 4, 6, 8, 5.0, 80.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Triceps Pushdown", 3, 10, 12, 2.5, 35.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Curl", 3, 10, 12, 2.5, 35.0, DayOfWeek.MONDAY)
    );

    private static final List<ExerciseTemplate> LOWER_1_PLAN = List.of(
            new ExerciseTemplate("Barbell Back Squat", 4, 5, 5, 10.0, 185.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Romanian Deadlift", 4, 6, 6, 5.0, 155.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Bulgarian Split Squat", 3, 8, 8, 5.0, 40.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Hanging Leg Raise", 3, 10, 15, 0.0, 0.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Cable Pull-Through", 3, 12, 15, 2.5, 55.0, DayOfWeek.TUESDAY)
    );

    private static final List<ExerciseTemplate> UPPER_2_PLAN = List.of(
            new ExerciseTemplate("Incline Barbell Bench Press", 4, 8, 10, 5.0, 95.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Lat Pulldown", 4, 8, 10, 5.0, 70.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Seated Row", 3, 10, 12, 2.5, 60.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Face Pull", 3, 12, 15, 2.5, 25.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Lateral Raise", 3, 12, 15, 2.5, 15.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Close-Grip Bench Press", 3, 8, 10, 5.0, 95.0, DayOfWeek.THURSDAY)
    );

    private static final List<ExerciseTemplate> LOWER_2_PLAN = List.of(
            new ExerciseTemplate("Front Squat", 4, 6, 8, 10.0, 135.0, DayOfWeek.FRIDAY),
            new ExerciseTemplate("Barbell Reverse Lunge", 3, 10, 10, 5.0, 45.0, DayOfWeek.FRIDAY),
            new ExerciseTemplate("Barbell Hip Thrust", 3, 10, 12, 5.0, 135.0, DayOfWeek.FRIDAY),
            new ExerciseTemplate("Cable Pull-Through", 3, 12, 15, 2.5, 55.0, DayOfWeek.FRIDAY),
            new ExerciseTemplate("Standing Barbell Calf Raise", 4, 15, 20, 5.0, 95.0, DayOfWeek.FRIDAY),
            new ExerciseTemplate("Hanging Knee Raise", 3, 12, 15, 0.0, 0.0, DayOfWeek.FRIDAY)
    );

    private static final String HTML_PAGE = """
            <!DOCTYPE html>
            <html lang=\"en\">
            <head>
                <meta charset=\"UTF-8\"/>
                <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>
                <title>Workout Tracker</title>
                <style>
                    :root { color-scheme: light dark; }
                    body { font-family: Arial, sans-serif; margin: 0; background: #0f172a; color: #e2e8f0; }
                    .container { max-width: 1200px; margin: 40px auto; padding: 20px; }
                    h1 { margin-bottom: 8px; }
                    .meta { color: #cbd5e1; margin-bottom: 20px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
                    th, td { border: 1px solid #334155; padding: 10px; text-align: center; }
                    th { background: #1e293b; }
                    input { width: 100%; box-sizing: border-box; padding: 8px; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #f8fafc; text-align: center; }
                    button { background: #22c55e; color: #052e16; border: none; border-radius: 8px; padding: 10px 18px; font-weight: bold; cursor: pointer; }
                    .status { margin-top: 20px; min-height: 24px; color: #86efac; }
                    .error { color: #fca5a5; }
                </style>
            </head>
            <body>
                <div class=\"container\">
                    <h1>Workout Tracker</h1>
                    <div id=\"dayMeta\" class=\"meta\"></div>
                    <div style=\"margin-bottom: 16px;\">
                        <label for=\"routineSelect\">Workout: </label>
                        <select id=\"routineSelect\" style=\"padding: 8px; border-radius: 6px; background: #0f172a; color: #f8fafc; border: 1px solid #475569;\">
                            <option value=\"Upper 1\">Upper 1</option>
                            <option value=\"Upper 2\">Upper 2</option>
                            <option value=\"Lower 1\">Lower 1</option>
                            <option value=\"Lower 2\">Lower 2</option>
                        </select>
                    </div>
                    <form id=\"workoutForm\">
                        <table>
                            <thead>
                                <tr>
                                    <th>Exercise</th>
                                    <th>Target Sets</th>
                                    <th>Rep Floor</th>
                                    <th>Rep Ceiling</th>
                                    <th>Target Weight</th>
//                                    <th>Last Max Reps</th>
//                                    <th>Set 1</th>
//                                    <th>Set 2</th>
//                                    <th>Set 3</th>
//                                    <th>Set 4</th>
                                </tr>
                            </thead>
                            <tbody id=\"exerciseRows\"></tbody>
                        </table>
                        <div style=\"margin-top: 20px;\">
                            <button type=\"submit\">Save workout</button>
                        </div>
                    </form>
                    <div id=\"status\" class=\"status\"></div>
                </div>

                <script>
                    async function loadPlan() {
                        const routine = document.getElementById('routineSelect').value;
                        const response = await fetch('/api/workout-plan?routine=' + encodeURIComponent(routine));
                        const plan = await response.json();
                        const rows = document.getElementById('exerciseRows');
                        const meta = document.getElementById('dayMeta');
                        const routineSelect = document.getElementById('routineSelect');
                        routineSelect.value = plan.routine || routine;
                        const deloadLabel = plan.deload ? ' • Deload week' : '';
                        meta.textContent = `${plan.day} • ${plan.date} • ${plan.routine}${deloadLabel}`;
                        rows.innerHTML = '';

                        if (!plan.exercises || plan.exercises.length === 0) {
                            rows.innerHTML = '<tr><td colspan="10">Rest day — recovery, mobility, and light cardio recommended.</td></tr>';
                            return;
                        }

                        plan.exercises.forEach((exercise) => {
                            const row = document.createElement('tr');
                            const setInputs = [];
                            for (let i = 0; i < exercise.sets; i++) {
                                setInputs.push(`<td><input type=\"number\" min=\"0\" max=\"30\" step=\"1\" data-name=\"${exercise.name}\" data-set-index=\"${i}\" placeholder=\"${i + 1}\" /></td>`);
                            }
                            row.innerHTML = `
                                <td>${exercise.name}</td>
                                <td>${exercise.sets}</td>
                                <td>${exercise.repFloor}</td>
                                <td>${exercise.repCeiling}</td>
                                <td>${exercise.targetWeight} lb</td>
                                <td>${exercise.lastMaxReps || 0}</td>
                                ${setInputs.join('')}
                            `;
                            rows.appendChild(row);
                        });
                    }

                    document.getElementById('routineSelect').addEventListener('change', loadPlan);

                    document.getElementById('workoutForm').addEventListener('submit', async (event) => {
                        event.preventDefault();
                        const inputs = document.querySelectorAll('input[data-name]');
                        const params = new URLSearchParams();
                        const groups = {};

                        inputs.forEach((input) => {
                            const value = Number(input.value);
                            if (!isNaN(value) && value >= 0) {
                                const key = input.dataset.name;
                                if (!groups[key]) {
                                    groups[key] = [];
                                }
                                groups[key].push(value);
                            }
                        });

                        Object.entries(groups).forEach(([exerciseName, values]) => {
                            const setValues = values.filter((value) => value >= 0);
                            if (setValues.length > 0) {
                                params.append(exerciseName, setValues.join('|'));
                            }
                        });

                        const status = document.getElementById('status');
                        if (Object.keys(groups).length === 0) {
                            status.textContent = 'No reps entered. Nothing was saved.';
                            status.classList.add('error');
                            return;
                        }

                        params.append('routine', document.getElementById('routineSelect').value);

                        const response = await fetch('/api/log-workout', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
                            body: params.toString()
                        });

                        const result = await response.json();
                        status.textContent = result.success ? 'Workout saved successfully.' : result.message;
                        status.classList.toggle('error', !result.success);
                        if (result.success) {
                            loadPlan();
                        }
                    });

                    loadPlan();
                </script>
            </body>
            </html>
            """;

    private static String buildHtmlPage() {
        return HTML_PAGE;
    }

    public static class ExerciseTemplate {
        private final String name;
        private final int sets;
        private final int repFloor;
        private final int repCeiling;
        private final double incrementLb;
        private final double baseWeight;
        private final List<DayOfWeek> days;

        public ExerciseTemplate(String name, int sets, int repFloor, int repCeiling, double incrementLb, double baseWeight, DayOfWeek day) {
            this.name = name;
            this.sets = sets;
            this.repFloor = repFloor;
            this.repCeiling = repCeiling;
            this.incrementLb = incrementLb;
            this.baseWeight = baseWeight;
            this.days = List.of(day);
        }
    }

    public static class WorkoutEntry {
        private final LocalDate date;
        private final String dayName;
        private final List<ExerciseLog> exercises;

        public WorkoutEntry(LocalDate date, String dayName) {
            this.date = date;
            this.dayName = dayName;
            this.exercises = new ArrayList<>();
        }

        public LocalDate getDate() {
            return date;
        }
    }

    public static class ExerciseLog {
        private final String exerciseName;
        private final double weight;
        private final List<Integer> repsBySet;

        public ExerciseLog(String exerciseName, List<Integer> repsBySet) {
            this.exerciseName = exerciseName;
            this.weight = 0.0;
            this.repsBySet = new ArrayList<>(repsBySet);
        }
    }

    public static class ProgressionPlan {
        private final double targetWeight;
        private final double lastWeight;
        private final int lastMaxReps;
        private final boolean deload;

        public ProgressionPlan(double targetWeight, double lastWeight, int lastMaxReps, boolean deload) {
            this.targetWeight = targetWeight;
            this.lastWeight = lastWeight;
            this.lastMaxReps = lastMaxReps;
            this.deload = deload;
        }
    }
}
