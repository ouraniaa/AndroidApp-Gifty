package com.example.androidapp.greeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

//HttpTransport backed by the platform HttpURLConnection (no extra libraries).
//Only HTTPS URLs are allowed, redirects are not followed and response bodies are size-capped.
public class UrlConnectionTransport implements HttpTransport {

    public static final int CONNECT_TIMEOUT_MS = 10_000;
    //Longer than the Worker's own 12 s Gemini timeout, so the server can answer with a clean error
    public static final int READ_TIMEOUT_MS = 20_000;
    public static final int MAX_RESPONSE_BYTES = 16 * 1024;

    @Override
    public Response post(String url, Map<String, String> headers, String body) throws IOException {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("Only HTTPS URLs are allowed");
        }

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setFixedLengthStreamingMode(payload.length);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readCapped(stream);
            return new Response(status, text, connection.getHeaderField("Retry-After"));
        } finally {
            connection.disconnect();
        }
    }

    private static String readCapped(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Response too large");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
