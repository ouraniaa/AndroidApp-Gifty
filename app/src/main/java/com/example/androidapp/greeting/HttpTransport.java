package com.example.androidapp.greeting;

import java.io.IOException;
import java.util.Map;

//Minimal blocking HTTP client used by BackendGreetingCardGenerator.
//It exists so the generator can be unit tested with a fake transport instead of the network.
//Implementations are called on a background thread and may block.
public interface HttpTransport {

    //Send a POST request. Throws IOException for connection problems and timeouts
    //(java.net.SocketTimeoutException); HTTP error statuses are returned, not thrown.
    Response post(String url, Map<String, String> headers, String body) throws IOException;

    final class Response {
        private final int statusCode;
        private final String body;
        private final String retryAfter;

        public Response(int statusCode, String body, String retryAfter) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
            this.retryAfter = retryAfter;
        }

        public Response(int statusCode, String body) {
            this(statusCode, body, null);
        }

        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }

        //Raw Retry-After header value, or null
        public String getRetryAfter() { return retryAfter; }
    }
}
