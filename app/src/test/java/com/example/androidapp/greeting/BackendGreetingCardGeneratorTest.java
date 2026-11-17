package com.example.androidapp.greeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.androidapp.greeting.GreetingCardRequest.Language;
import com.example.androidapp.greeting.GreetingCardRequest.Occasion;
import com.example.androidapp.greeting.GreetingCardRequest.Relationship;
import com.example.androidapp.greeting.GreetingCardRequest.Tone;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

//Uses a fake HttpTransport: no network and no real Gemini requests.
public class BackendGreetingCardGeneratorTest {

    private static final String BASE_URL = "https://gifty-ai-api.example.workers.dev";
    private static final String INSTALL_ID = "3f2a9c1e-8b7d-4e6f-9a1b-2c3d4e5f6a7b";

    //Records the request and returns a canned response (or throws)
    private static final class FakeTransport implements HttpTransport {
        String url;
        Map<String, String> headers;
        String body;
        int calls = 0;
        private final Response response;
        private final IOException failure;

        FakeTransport(Response response) { this.response = response; this.failure = null; }
        FakeTransport(IOException failure) { this.response = null; this.failure = failure; }

        @Override
        public Response post(String url, Map<String, String> headers, String body) throws IOException {
            calls++;
            this.url = url;
            this.headers = headers;
            this.body = body;
            if (failure != null) throw failure;
            return response;
        }
    }

    //Queues tasks so the test can check which "thread" ran what
    private static final class QueueExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runAll() {
            while (!tasks.isEmpty()) tasks.remove(0).run();
        }
    }

    private static final class RecordingCallback implements GreetingCardGenerator.Callback {
        GreetingCardResult result;
        String error;
        int calls = 0;
        @Override public void onSuccess(GreetingCardResult r) { calls++; result = r; }
        @Override public void onError(String message) { calls++; error = message; }
    }

    private static final Executor DIRECT = Runnable::run;

    private static GreetingCardRequest request(String name) {
        return new GreetingCardRequest(Occasion.NAME_DAY, Relationship.FRIEND, Tone.FUNNY, Language.GREEK, name);
    }

    private static RecordingCallback run(FakeTransport transport, GreetingCardRequest request) {
        BackendGreetingCardGenerator generator =
                new BackendGreetingCardGenerator(BASE_URL, INSTALL_ID, transport, DIRECT, DIRECT);
        RecordingCallback callback = new RecordingCallback();
        generator.generate(request, callback);
        return callback;
    }

    private static Set<String> keys(JSONObject json) {
        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = json.keys(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    // ---- request serialization ----

    @Test
    public void sendsExactlyTheFieldsTheBackendExpects() throws Exception {
        FakeTransport transport = new FakeTransport(new HttpTransport.Response(200, "{\"message\":\"Hi\"}"));
        run(transport, request("  Νίκη "));

        assertEquals(BASE_URL + "/generate-greeting", transport.url);
        JSONObject sent = new JSONObject(transport.body);
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                "occasion", "relationship", "tone", "language", "recipientName")), keys(sent));
        assertEquals("name_day", sent.getString("occasion"));
        assertEquals("friend", sent.getString("relationship"));
        assertEquals("funny", sent.getString("tone"));
        assertEquals("el", sent.getString("language"));
        assertEquals("Νίκη", sent.getString("recipientName"));
    }

    @Test
    public void omitsRecipientNameWhenNotGiven() throws Exception {
        FakeTransport transport = new FakeTransport(new HttpTransport.Response(200, "{\"message\":\"Hi\"}"));
        run(transport, new GreetingCardRequest(Occasion.BIRTHDAY, Relationship.MUM, Tone.WARM, Language.ENGLISH, "   "));

        JSONObject sent = new JSONObject(transport.body);
        assertFalse(sent.has("recipientName"));
        assertEquals("birthday", sent.getString("occasion"));
        assertEquals("en", sent.getString("language"));
    }

    @Test
    public void sendsJsonContentTypeAndInstallIdButNoSecrets() {
        FakeTransport transport = new FakeTransport(new HttpTransport.Response(200, "{\"message\":\"Hi\"}"));
        run(transport, request(null));

        assertTrue(transport.headers.get("Content-Type").startsWith("application/json"));
        assertEquals(INSTALL_ID, transport.headers.get("X-Gifty-Install-Id"));
        for (String header : transport.headers.keySet()) {
            String lower = header.toLowerCase();
            assertFalse("unexpected credential header " + header,
                    lower.contains("key") || lower.contains("authorization"));
        }
    }

    @Test
    public void trailingSlashInBaseUrlIsHandled() {
        BackendGreetingCardGenerator generator = new BackendGreetingCardGenerator(
                BASE_URL + "/", INSTALL_ID, new FakeTransport((IOException) null), DIRECT, DIRECT);
        assertEquals(BASE_URL + "/generate-greeting", generator.getEndpointUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesPlainHttp() {
        new BackendGreetingCardGenerator("http://example.com", INSTALL_ID,
                new FakeTransport((IOException) null), DIRECT, DIRECT);
    }

    // ---- response parsing ----

    @Test
    public void parsesGeminiSuccessAsRealAiResult() {
        RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(200,
                "{\"message\":\"Γεια σου Νίκη!\\n\\nΧρόνια πολλά!\",\"source\":\"gemini\","
                        + "\"model\":\"gemini-3.5-flash-lite\",\"language\":\"el\",\"mock\":false}")), request("Νίκη"));

        assertEquals(1, callback.calls);
        assertNull(callback.error);
        assertNotNull(callback.result);
        assertEquals("Γεια σου Νίκη!\n\nΧρόνια πολλά!", callback.result.getMessage());
        assertFalse(callback.result.isMock());
        assertEquals("gemini", callback.result.getSource());
    }

    @Test
    public void keepsTheServersMockFlag() {
        RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(200,
                "{\"message\":\"Hello\",\"source\":\"mock-template\",\"mock\":true}")), request(null));
        assertTrue(callback.result.isMock());
    }

    @Test
    public void invalidSuccessBodiesAreErrorsNotResults() {
        String[] bodies = {"not json", "{}", "{\"message\":\"   \"}", "{\"message\":42}", "[]", ""};
        for (String body : bodies) {
            RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(200, body)), request(null));
            assertNull("no result expected for: " + body, callback.result);
            assertEquals(BackendGreetingCardGenerator.MSG_INVALID_RESPONSE, callback.error);
        }
    }

    // ---- HTTP errors and rate limits ----

    @Test
    public void rateLimitUsesRetryAfterSeconds() {
        RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests.\",\"retryable\":true}}", "60")),
                request(null));
        assertNull(callback.result);
        assertTrue(callback.error.contains("60 seconds"));
    }

    @Test
    public void rateLimitWithoutRetryAfterStillHasAFriendlyMessage() {
        RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(429, "")), request(null));
        assertEquals(BackendGreetingCardGenerator.MSG_RATE_LIMITED, callback.error);
    }

    @Test
    public void showsTheBackendsUserSafeErrorMessage() {
        RecordingCallback callback = run(new FakeTransport(new HttpTransport.Response(503,
                "{\"error\":{\"code\":\"UPSTREAM_RATE_LIMITED\",\"message\":\"The card writer is busy right now. Please try again shortly.\",\"retryable\":true}}",
                "17")), request(null));
        assertEquals("The card writer is busy right now. Please try again shortly.", callback.error);
    }

    @Test
    public void fallsBackToGenericMessagesForUnreadableErrors() {
        assertEquals(BackendGreetingCardGenerator.MSG_SERVER,
                run(new FakeTransport(new HttpTransport.Response(502, "<html>Bad gateway</html>")), request(null)).error);
        assertEquals(BackendGreetingCardGenerator.MSG_BAD_REQUEST,
                run(new FakeTransport(new HttpTransport.Response(400, "{}")), request(null)).error);

        // Overly long server messages are not shown
        String longMessage = new String(new char[300]).replace('\0', 'x');
        assertEquals(BackendGreetingCardGenerator.MSG_SERVER,
                run(new FakeTransport(new HttpTransport.Response(500,
                        "{\"error\":{\"message\":\"" + longMessage + "\"}}")), request(null)).error);
    }

    // ---- connection failures ----

    @Test
    public void timeoutsAndConnectionFailuresAreReported() {
        assertEquals(BackendGreetingCardGenerator.MSG_TIMEOUT,
                run(new FakeTransport(new SocketTimeoutException("read timed out")), request(null)).error);
        assertEquals(BackendGreetingCardGenerator.MSG_OFFLINE,
                run(new FakeTransport(new UnknownHostException("no dns")), request(null)).error);
        assertEquals(BackendGreetingCardGenerator.MSG_OFFLINE,
                run(new FakeTransport(new ConnectException("refused")), request(null)).error);
        assertEquals(BackendGreetingCardGenerator.MSG_OFFLINE,
                run(new FakeTransport(new IOException("reset")), request(null)).error);
    }

    @Test
    public void failuresNeverFallBackToMockTemplates() {
        RecordingCallback callback = run(new FakeTransport(new IOException("offline")), request(null));
        assertEquals(1, callback.calls);
        assertNull(callback.result);
        assertNotNull(callback.error);
    }

    // ---- threading ----

    @Test
    public void networkRunsOnBackgroundAndCallbackOnMain() {
        FakeTransport transport = new FakeTransport(new HttpTransport.Response(200, "{\"message\":\"Hi\"}"));
        QueueExecutor background = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        BackendGreetingCardGenerator generator =
                new BackendGreetingCardGenerator(BASE_URL, INSTALL_ID, transport, background, main);
        RecordingCallback callback = new RecordingCallback();

        generator.generate(request(null), callback);
        // Nothing happens on the calling thread
        assertEquals(0, transport.calls);
        assertEquals(0, callback.calls);

        background.runAll();
        // The network call ran in the background, but the callback waits for the main thread
        assertEquals(1, transport.calls);
        assertEquals(0, callback.calls);
        assertEquals(1, main.tasks.size());

        main.runAll();
        assertEquals(1, callback.calls);
        assertEquals("Hi", callback.result.getMessage());
    }
}
