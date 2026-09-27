import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Home Assistant app only: prints the URL away cars reach this Home Assistant on (its Nabu Casa
 * remote UI, else its external URL), or nothing. run.sh hands it to the hub as
 * OAA_PUBLIC_NODE_URL; the hub itself knows nothing about Home Assistant. Diagnostics go to stderr.
 *
 * Usage: java -cp oaa-hub.jar:. HaPublicUrl [waitSeconds]
 */
public final class HaPublicUrl {
    private static final String CORE_WS =
        System.getenv().getOrDefault("OAA_HA_CORE_WS", "ws://supervisor/core/websocket");
    private static final Duration STEP = Duration.ofSeconds(5);

    private record Answer(String url, boolean settled, String note) {}

    public static void main(String[] args) throws Exception {
        String token = System.getenv("SUPERVISOR_TOKEN");
        if (token == null || token.isBlank()) return;
        long waitSeconds = args.length > 0 ? Long.parseLong(args[0]) : 60;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        Answer last = null;
        while (true) {
            try {
                last = ask(token);
            } catch (Exception e) {
                last = new Answer(null, false, "Home Assistant not reachable yet: " + e);
            }
            if (last.settled() || System.nanoTime() > deadline) break;
            Thread.sleep(3_000);
        }
        System.err.println("public URL from Home Assistant: " + (last.url() != null ? last.url() : "none") + " (" + last.note() + ")");
        if (last.url() != null) System.out.println(last.url());
    }

    /** One round over Core's WebSocket API: `cloud/status`, then `get_config` for the external URL. */
    private static Answer ask(String token) throws Exception {
        BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newBuilder().connectTimeout(STEP).build()
            .newWebSocketBuilder()
            .buildAsync(URI.create(CORE_WS), new Collector(inbox))
            .get(STEP.toSeconds(), TimeUnit.SECONDS);
        try {
            expect(inbox, "auth_required");
            ws.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString(), true);
            expect(inbox, "auth_ok");
            ws.sendText("{\"id\":1,\"type\":\"cloud/status\"}", true);
            ws.sendText("{\"id\":2,\"type\":\"get_config\"}", true);
            JSONObject cloud = null;
            JSONObject config = null;
            while (cloud == null || config == null) {
                JSONObject msg = take(inbox);
                if (!"result".equals(msg.optString("type"))) continue;
                JSONObject result = msg.optBoolean("success") ? msg.optJSONObject("result") : null;
                if (result == null) result = new JSONObject();
                if (msg.optInt("id") == 1) cloud = result; else if (msg.optInt("id") == 2) config = result;
            }
            String external = trimSlash(config.optString("external_url", ""));
            if (!cloud.optBoolean("logged_in")) {
                return orExternal(external, true, "not logged in to Nabu Casa");
            }
            JSONObject prefs = cloud.optJSONObject("prefs");
            if (prefs == null || !prefs.optBoolean("remote_enabled")) {
                return orExternal(external, true, "Nabu Casa remote access is off");
            }
            String domain = cloud.optString("remote_domain", "");
            if (domain.isEmpty() || "null".equals(domain)) {
                return orExternal(external, false, "Nabu Casa remote domain not known yet");
            }
            return new Answer("https://" + domain, true, "Nabu Casa remote access");
        } finally {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Closing is best effort; abort below either way.
            }
            ws.abort();
        }
    }

    private static Answer orExternal(String external, boolean settled, String why) {
        if (external.isEmpty()) return new Answer(null, settled, why + ", no external URL set");
        return new Answer(external, settled, why + ", using the external URL");
    }

    private static void expect(BlockingQueue<JSONObject> inbox, String type) throws Exception {
        JSONObject msg = take(inbox);
        if (!type.equals(msg.optString("type"))) throw new IllegalStateException("expected " + type + ", got " + msg);
    }

    private static JSONObject take(BlockingQueue<JSONObject> inbox) throws Exception {
        JSONObject msg = inbox.poll(STEP.toSeconds(), TimeUnit.SECONDS);
        if (msg == null) throw new IllegalStateException("no answer from Home Assistant");
        return msg;
    }

    private static String trimSlash(String url) {
        return url == null || "null".equals(url) ? "" : url.trim().replaceAll("/+$", "");
    }

    private static final class Collector implements WebSocket.Listener {
        private final BlockingQueue<JSONObject> inbox;
        private final StringBuilder partial = new StringBuilder();

        Collector(BlockingQueue<JSONObject> inbox) {
            this.inbox = inbox;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    inbox.add(new JSONObject(text));
                } catch (Exception ignored) {
                    // Core only sends JSON objects; anything else is not an answer we wait for.
                }
            }
            ws.request(1);
            return null;
        }
    }
}
