package com.example.androidapp.greeting;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

//GreetingCardGenerator that asks the Gifty backend (Cloudflare Worker -> Gemini) for a message.
//
//The app never talks to Gemini directly and holds no API key: the Worker keeps the key as a
//Cloudflare Secret. Every failure is reported through onError; there is no silent fallback to
//the mock templates, so a template can never be shown as if Gemini had written it.
public class BackendGreetingCardGenerator implements GreetingCardGenerator {

    public static final String GREETING_PATH = "/generate-greeting";
    public static final String INSTALL_ID_HEADER = "X-Gifty-Install-Id";
    static final int MAX_ERROR_MESSAGE_LENGTH = 200;

    static final String MSG_TIMEOUT = "The card writer took too long to answer. Please try again.";
    static final String MSG_OFFLINE = "Couldn't reach the card writer. Check your internet connection and try again.";
    static final String MSG_INVALID_RESPONSE = "Received an unexpected response. Please try again.";
    static final String MSG_RATE_LIMITED = "You're creating cards very quickly. Please wait a minute and try again.";
    static final String MSG_SERVER = "The card writer is unavailable right now. Please try again later.";
    static final String MSG_BAD_REQUEST = "Some of the card details were not accepted. Please check them and try again.";

    private final String endpointUrl;
    private final String installId;
    private final HttpTransport transport;
    private final Executor backgroundExecutor;
    private final Executor mainExecutor;

    public BackendGreetingCardGenerator(String baseUrl, String installId, HttpTransport transport,
                                        Executor backgroundExecutor, Executor mainExecutor) {
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Backend URL must use HTTPS");
        }
        if (installId == null || installId.isEmpty()) {
            throw new IllegalArgumentException("Install ID is required");
        }
        this.endpointUrl = stripTrailingSlash(baseUrl) + GREETING_PATH;
        this.installId = installId;
        this.transport = transport;
        this.backgroundExecutor = backgroundExecutor;
        this.mainExecutor = mainExecutor;
    }

    String getEndpointUrl() { return endpointUrl; }

    @Override
    public void generate(GreetingCardRequest request, Callback callback) {
        backgroundExecutor.execute(() -> {
            Outcome outcome = performRequest(request);
            mainExecutor.execute(() -> {
                if (outcome.result != null) {
                    callback.onSuccess(outcome.result);
                } else {
                    callback.onError(outcome.errorMessage);
                }
            });
        });
    }

    //Runs on the background thread. Never throws.
    private Outcome performRequest(GreetingCardRequest request) {
        HttpTransport.Response response;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("Accept", "application/json");
            headers.put(INSTALL_ID_HEADER, installId);
            response = transport.post(endpointUrl, headers, toRequestJson(request));
        } catch (SocketTimeoutException e) {
            return Outcome.error(MSG_TIMEOUT);
        } catch (UnknownHostException | ConnectException e) {
            return Outcome.error(MSG_OFFLINE);
        } catch (IOException e) {
            return Outcome.error(MSG_OFFLINE);
        } catch (JSONException | RuntimeException e) {
            return Outcome.error(MSG_INVALID_RESPONSE);
        }

        if (response.getStatusCode() == 200) {
            return parseSuccess(response.getBody());
        }
        return Outcome.error(errorMessageFor(response));
    }

    //Exactly the fields the Worker accepts; recipientName only when the user entered one
    static String toRequestJson(GreetingCardRequest request) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("occasion", request.getOccasion().getKey());
        json.put("relationship", request.getRelationship().getKey());
        json.put("tone", request.getTone().getKey());
        json.put("language", request.getLanguage().getKey());
        if (request.hasRecipientName()) {
            json.put("recipientName", request.getRecipientName());
        }
        return json.toString();
    }

    static Outcome parseSuccess(String body) {
        try {
            JSONObject json = new JSONObject(body);
            Object message = json.opt("message");
            if (!(message instanceof String) || ((String) message).trim().isEmpty()) {
                return Outcome.error(MSG_INVALID_RESPONSE);
            }
            //Trust the server's own labels; default to "not mock" only for the real backend
            boolean mock = json.optBoolean("mock", false);
            String source = json.optString("source", "gemini");
            return Outcome.success(new GreetingCardResult(((String) message).trim(), mock, source));
        } catch (JSONException | IllegalArgumentException e) {
            return Outcome.error(MSG_INVALID_RESPONSE);
        }
    }

    static String errorMessageFor(HttpTransport.Response response) {
        int status = response.getStatusCode();

        if (status == 429) {
            Integer seconds = parseRetryAfter(response.getRetryAfter());
            if (seconds != null) {
                return "You're creating cards very quickly. Please wait " + seconds + " seconds and try again.";
            }
            return MSG_RATE_LIMITED;
        }

        //The Worker's error messages are written to be shown to users
        String serverMessage = readServerErrorMessage(response.getBody());
        if (serverMessage != null) return serverMessage;

        if (status >= 500) return MSG_SERVER;
        if (status >= 400) return MSG_BAD_REQUEST;
        return MSG_INVALID_RESPONSE;
    }

    static String readServerErrorMessage(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error == null) return null;
            Object message = error.opt("message");
            if (!(message instanceof String)) return null;
            String text = ((String) message).trim();
            if (text.isEmpty() || text.length() > MAX_ERROR_MESSAGE_LENGTH) return null;
            return text;
        } catch (JSONException e) {
            return null;
        }
    }

    static Integer parseRetryAfter(String value) {
        if (value == null) return null;
        try {
            int seconds = Integer.parseInt(value.trim());
            return seconds > 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static final class Outcome {
        final GreetingCardResult result;
        final String errorMessage;

        private Outcome(GreetingCardResult result, String errorMessage) {
            this.result = result;
            this.errorMessage = errorMessage;
        }

        static Outcome success(GreetingCardResult result) { return new Outcome(result, null); }
        static Outcome error(String message) { return new Outcome(null, message); }
    }
}
