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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class WorkoutTrackerApp {
    private static final int PORT = 8080;
    private static final Path DATA_FILE = Paths.get(System.getenv().getOrDefault("WORKOUT_DATA_DIR", "."), "workout_history.txt");
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
        List<ExerciseTemplate> templates = getExercisesForDay(today.getDayOfWeek());
        StringBuilder json = new StringBuilder();
        json.append("{\"date\":\"").append(today.format(FILE_DATE_FORMAT)).append("\",");
        json.append("\"day\":\"").append(formatDayName(today.getDayOfWeek())).append("\",");
        json.append("\"exercises\":[");

        for (int i = 0; i < templates.size(); i++) {
            ExerciseTemplate template = templates.get(i);
            ProgressionPlan plan = recommendPlan(template);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"name\":\"").append(template.name).append("\",");
            json.append("\"sets\":").append(template.sets).append(",");
            json.append("\"minReps\":").append(template.minReps).append(",");
            json.append("\"maxReps\":").append(template.maxReps).append(",");
            json.append("\"targetWeight\":").append(plan.targetWeight).append(",");
            json.append("\"lastWeight\":").append(plan.lastWeight).append(",");
            json.append("\"lastReps\":").append(plan.lastReps).append("}");
        }
        json.append("]}");

        sendResponse(exchange, 200, "application/json; charset=UTF-8", json.toString());
    }

    private void saveWorkoutLog(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json; charset=UTF-8", "{\"success\":false,\"message\":\"Method not allowed\"}");
            return;
        }

        String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<ExerciseLog> logs = parseWorkoutBody(rawBody);
        if (logs.isEmpty()) {
            sendResponse(exchange, 400, "application/json; charset=UTF-8", "{\"success\":false,\"message\":\"No workout data was received\"}");
            return;
        }

        LocalDate today = LocalDate.now();
        WorkoutEntry entry = new WorkoutEntry(today, today.getDayOfWeek().name());
        entry.exercises.addAll(logs);
        workouts.add(entry);
        saveWorkouts();

        sendResponse(exchange, 200, "application/json; charset=UTF-8", "{\"success\":true,\"message\":\"Workout saved\"}");
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
                json.append("{\"name\":\"").append(log.exerciseName).append("\",\"weight\":").append(log.weight).append(",\"reps\":").append(log.reps).append(",\"sets\":").append(log.sets).append("}");
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

                String exerciseName = values[0].replace("+", " ");
                String[] parts = values[1].split("\\|");
                if (parts.length != 3) {
                    continue;
                }

                double weight = Double.parseDouble(parts[0]);
                int reps = Integer.parseInt(parts[1]);
                int sets = Integer.parseInt(parts[2]);
                if (weight > 0) {
                    logs.add(new ExerciseLog(exerciseName, weight, reps, sets));
                }
            }
        } catch (Exception ignored) {
            return logs;
        }

        return logs;
    }

    private void loadWorkouts() {
        Path parent = DATA_FILE.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                System.out.println("Could not create workout data directory: " + e.getMessage());
                return;
            }
        }

        if (!Files.exists(DATA_FILE)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(DATA_FILE);
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
                    if (parts.length < 4) {
                        continue;
                    }
                    entry.exercises.add(new ExerciseLog(parts[0], Double.parseDouble(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                }

                workouts.add(entry);
            }
        } catch (IOException e) {
            System.out.println("Could not read workout file: " + e.getMessage());
        }
    }

    private void saveWorkouts() {
        try {
            Path parent = DATA_FILE.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            List<String> lines = new ArrayList<>();
            for (WorkoutEntry entry : workouts) {
                StringBuilder line = new StringBuilder();
                line.append(entry.date.format(FILE_DATE_FORMAT)).append("|").append(entry.dayName);
                for (ExerciseLog log : entry.exercises) {
                    line.append("|").append(log.exerciseName).append("~").append(log.weight).append("~").append(log.reps).append("~").append(log.sets);
                }
                lines.add(line.toString());
            }
            Files.write(DATA_FILE, lines);
        } catch (IOException e) {
            System.out.println("Could not save workout file: " + e.getMessage());
        }
    }

    private ProgressionPlan recommendPlan(ExerciseTemplate template) {
        ExerciseLog last = findMostRecentLog(template.name);
        if (last == null) {
            return new ProgressionPlan(template.baseWeight, 0.0, 0);
        }

        double targetWeight = last.weight;
        if (last.reps >= template.maxReps && last.sets >= template.sets) {
            targetWeight = roundToHalf(last.weight + template.incrementLb);
        } else if (last.reps < template.minReps || last.sets < template.sets) {
            targetWeight = roundToHalf(last.weight);
        }
        return new ProgressionPlan(targetWeight, last.weight, last.reps);
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
        List<ExerciseTemplate> templates = new ArrayList<>();
        for (ExerciseTemplate template : DEFAULT_PLAN) {
            if (template.days.contains(dayOfWeek)) {
                templates.add(template);
            }
        }
        return templates;
    }

    private static String formatDayName(DayOfWeek day) {
        String value = day.name();
        return value.substring(0, 1) + value.substring(1).toLowerCase();
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

    private static final List<ExerciseTemplate> DEFAULT_PLAN = List.of(
            new ExerciseTemplate("Barbell Bench Press", 4, 5, 5, 5.0, 115.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Barbell Bent-Over Row", 4, 5, 5, 5.0, 125.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Landmine Press", 3, 6, 8, 5.0, 45.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Lat Pulldown", 4, 6, 8, 5.0, 80.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Triceps Pushdown", 3, 10, 12, 2.5, 35.0, DayOfWeek.MONDAY),
            new ExerciseTemplate("Cable Curl", 3, 10, 12, 2.5, 35.0, DayOfWeek.MONDAY),

            new ExerciseTemplate("Barbell Back Squat", 4, 5, 5, 10.0, 185.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Romanian Deadlift", 4, 6, 6, 5.0, 155.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Bulgarian Split Squat", 3, 8, 8, 5.0, 40.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Hanging Leg Raise", 3, 10, 15, 0.0, 0.0, DayOfWeek.TUESDAY),
            new ExerciseTemplate("Cable Pull-Through", 3, 12, 15, 2.5, 55.0, DayOfWeek.TUESDAY),

            new ExerciseTemplate("Incline Barbell Bench Press", 4, 8, 10, 5.0, 95.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Lat Pulldown", 4, 8, 10, 5.0, 70.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Seated Row", 3, 10, 12, 2.5, 60.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Face Pull", 3, 12, 15, 2.5, 25.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Cable Lateral Raise", 3, 12, 15, 2.5, 15.0, DayOfWeek.THURSDAY),
            new ExerciseTemplate("Close-Grip Bench Press", 3, 8, 10, 5.0, 95.0, DayOfWeek.THURSDAY),

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
                    .container { max-width: 1000px; margin: 40px auto; padding: 20px; }
                    h1 { margin-bottom: 8px; }
                    .meta { color: #cbd5e1; margin-bottom: 20px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
                    th, td { border: 1px solid #334155; padding: 12px; text-align: left; }
                    th { background: #1e293b; }
                    input { width: 100%; box-sizing: border-box; padding: 8px; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #f8fafc; }
                    button { background: #22c55e; color: #052e16; border: none; border-radius: 8px; padding: 10px 18px; font-weight: bold; cursor: pointer; }
                    .status { margin-top: 20px; min-height: 24px; color: #86efac; }
                    .error { color: #fca5a5; }
                </style>
            </head>
            <body>
                <div class=\"container\">
                    <h1>Workout Tracker</h1>
                    <div id=\"dayMeta\" class=\"meta\"></div>
                    <form id=\"workoutForm\">
                        <table>
                            <thead>
                                <tr>
                                    <th>Exercise</th>
                                    <th>Sets</th>
                                    <th>Reps</th>
                                    <th>Target</th>
                                    <th>Last Weight</th>
                                    <th>Last Reps</th>
                                    <th>Weight Used</th>
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
                        const response = await fetch('/api/workout-plan');
                        const plan = await response.json();
                        const rows = document.getElementById('exerciseRows');
                        const meta = document.getElementById('dayMeta');
                        meta.textContent = `${plan.day} • ${plan.date}`;
                        rows.innerHTML = '';

                        if (!plan.exercises || plan.exercises.length === 0) {
                            rows.innerHTML = '<tr><td colspan="7">Rest day — recovery, mobility, and light cardio recommended.</td></tr>';
                            return;
                        }

                        plan.exercises.forEach((exercise) => {
                            const repText = exercise.maxReps === exercise.minReps ? `${exercise.minReps}` : `${exercise.minReps}-${exercise.maxReps}`;
                            const row = document.createElement('tr');
                            row.innerHTML = `
                                <td>${exercise.name}</td>
                                <td>${exercise.sets}</td>
                                <td>${repText}</td>
                                <td>${exercise.targetWeight} lb</td>
                                <td>${exercise.lastWeight || 0} lb</td>
                                <td>${exercise.lastReps || 0}</td>
                                <td><input type=\"number\" min=\"0\" step=\"2.5\" name=\"${exercise.name}\" data-name=\"${exercise.name}\" placeholder=\"lb\" /></td>
                            `;
                            rows.appendChild(row);
                        });
                    }

                    document.getElementById('workoutForm').addEventListener('submit', async (event) => {
                        event.preventDefault();
                        const inputs = document.querySelectorAll('input[data-name]');
                        const params = new URLSearchParams();

                        inputs.forEach((input) => {
                            const weight = Number(input.value);
                            if (!isNaN(weight) && weight > 0) {
                                const reps = Number(prompt(`How many reps did you complete for ${input.dataset.name}?`, '5')) || 0;
                                const sets = Number(prompt(`How many sets did you complete for ${input.dataset.name}?`, '1')) || 0;
                                params.append(input.dataset.name, `${weight}|${reps}|${sets}`);
                            }
                        });

                        const status = document.getElementById('status');
                        if (params.toString() === '') {
                            status.textContent = 'No weight entered. Nothing was saved.';
                            status.classList.add('error');
                            return;
                        }

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
        private final int minReps;
        private final int maxReps;
        private final double incrementLb;
        private final double baseWeight;
        private final List<DayOfWeek> days;

        public ExerciseTemplate(String name, int sets, int minReps, int maxReps, double incrementLb, double baseWeight, DayOfWeek day) {
            this.name = name;
            this.sets = sets;
            this.minReps = minReps;
            this.maxReps = maxReps;
            this.incrementLb = incrementLb;
            this.baseWeight = baseWeight;
            this.days = List.of(day);
        }

        public ExerciseTemplate(String name, int sets, int minReps, int maxReps, double incrementLb, double baseWeight, DayOfWeek... days) {
            this.name = name;
            this.sets = sets;
            this.minReps = minReps;
            this.maxReps = maxReps;
            this.incrementLb = incrementLb;
            this.baseWeight = baseWeight;
            this.days = List.of(days);
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
        private final int reps;
        private final int sets;

        public ExerciseLog(String exerciseName, double weight, int reps, int sets) {
            this.exerciseName = exerciseName;
            this.weight = weight;
            this.reps = reps;
            this.sets = sets;
        }
    }

    public static class ProgressionPlan {
        private final double targetWeight;
        private final double lastWeight;
        private final int lastReps;

        public ProgressionPlan(double targetWeight, double lastWeight, int lastReps) {
            this.targetWeight = targetWeight;
            this.lastWeight = lastWeight;
            this.lastReps = lastReps;
        }
    }
}
