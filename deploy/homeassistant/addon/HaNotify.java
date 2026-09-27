import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONObject;

/**
 * Home Assistant app only: shows a persistent notification in Home Assistant (replacing an earlier
 * one with the same id). Failures go to stderr and never fail the caller.
 *
 * Usage: java -cp oaa-hub.jar:. HaNotify <id> <title> <message>
 */
public final class HaNotify {
    private static final String CORE_API =
        System.getenv().getOrDefault("OAA_HA_CORE_API", "http://supervisor/core/api");

    public static void main(String[] args) {
        String token = System.getenv("SUPERVISOR_TOKEN");
        if (token == null || token.isBlank() || args.length < 3) return;
        String body = new JSONObject()
            .put("notification_id", args[0])
            .put("title", args[1])
            .put("message", args[2])
            .toString();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CORE_API + "/services/persistent_notification/create"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) System.err.println("notification failed: HTTP " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            System.err.println("notification failed: " + e);
        }
    }
}
