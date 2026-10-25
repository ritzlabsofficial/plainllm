package com.ritzlabs.plainllm.service;

import com.ritzlabs.plainllm.pojo.BodyPojo;
import com.ritzlabs.plainllm.pojo.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParseException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ChatService {

    @Value("${plainllm.local.model.url}")
    private String modelUrl;

    @Value("${plainllm.model}")
    private String model;

    private final ExecutorService executor = Executors.newCachedThreadPool();


    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final Random RANDOM = new Random();

    public String singleResponse(String query) throws InterruptedException {

        int attempt = 0;

        Message message = setMessage(query);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = setJsonBody(objectMapper, message, false);

        HttpRequest request = setHttpRequest(jsonBody);

        HttpResponse<String> response = null;

        while (true) {
            attempt++;
            try {
                response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    return response.body();
                }

                if (statusCode == 429 || (statusCode >= 500 && statusCode <= 599)) {
                    if (attempt > MAX_RETRIES) {
                        throw new RuntimeException("Max retries reached. Failed with status code: " + statusCode);
                    }

                    long backoffMillis = parseRetryAfterHeader(response);
                    if (backoffMillis <= 0) {
                        backoffMillis = calculateExponentialBackoff(attempt);
                    }

                    log.warn("Received status {}. Retrying attempt {}/{} after {}ms...", statusCode, attempt, MAX_RETRIES, backoffMillis);

                    Thread.sleep(backoffMillis);
                    continue;
                }

                throw new IOException("Client error encountered, status code: " + statusCode);
            } catch (HttpTimeoutException ex) {
                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException("Request timed out after " + MAX_RETRIES + " retries.", ex);
                }
                long backoffMillis = calculateExponentialBackoff(attempt);
                log.warn("Request timed out. Retrying attempt {}/{} after {}ms...", attempt, MAX_RETRIES, backoffMillis);
                Thread.sleep(backoffMillis);
            }
            catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.info("Exception happened : {}", ex.getMessage());
                return null;
            }

            return response.body();
        }

    }

    public void streamLlmResponse(String query, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                HttpResponse<InputStream> response = rawStreamingResponse(query);

                if (response == null || response.statusCode() != 200) {
                    emitter.send(SseEmitter.event().name("error").data("Failed to connect to LLM"));
                    emitter.complete();
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) {
                            emitter.send(line);
                        }
                    }
                    emitter.complete();
                }

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
    }

    public HttpResponse<InputStream> rawStreamingResponse(String query) throws InterruptedException {

        int attempt = 0;
        Message message = setMessage(query);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = setJsonBody(objectMapper, message, true);

        HttpRequest request = setHttpRequest(jsonBody);


        while (true) {
            attempt++;
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    return response;
                }

                if (statusCode == 429 || (statusCode >= 500 && statusCode <= 599)) {
                    if (attempt > MAX_RETRIES) {
                        log.error("Max retries reached. Failed with status code: {}", statusCode);
                        response.body().close();
                        return null;
                    }

                    long backoffMillis = parseRetryAfterHeader(response);
                    if (backoffMillis <= 0) {
                        long exponential = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                        long jitter = (long) (exponential * (RANDOM.nextDouble() * 0.4 - 0.2));
                        backoffMillis = Math.max(100, exponential + jitter);
                    }

                    log.warn("Received status {}. Retrying attempt {}/{} after {}ms...",
                            statusCode, attempt, MAX_RETRIES, backoffMillis);

                    response.body().close();
                    Thread.sleep(backoffMillis);
                    continue;
                }

                log.error("Client error encountered, status code: {}", statusCode);
                response.body().close();
                return null;
            } catch (HttpTimeoutException ex) {
                if (attempt > MAX_RETRIES) {
                    throw new RuntimeException("Request timed out after " + MAX_RETRIES + " retries.", ex);
                }
                long backoffMillis = calculateExponentialBackoff(attempt);
                log.warn("Request timed out. Retrying attempt {}/{} after {}ms...", attempt, MAX_RETRIES, backoffMillis);
                Thread.sleep(backoffMillis);
            }
            catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.info("Exception happened : {}", ex.getMessage());
                return null;
            }
        }
    }

    private Message setMessage(String query) {

        Message message = new Message();
        message.setRole("user");
        message.setContent(query);

        return message;
    }

    private String setJsonBody(ObjectMapper objectMapper, Message message, boolean stream) {

        String jsonBody;

        try {
            jsonBody = objectMapper.writeValueAsString(BodyPojo.builder()
                    .stream(stream)
                    .messages(new ArrayList<>(List.of(message)))
                    .model(model)
                    .build());
        } catch (JsonParseException ex) {
            System.out.println("JSON Serialization Exception: " + ex.getMessage());
            return null;
        }

        return jsonBody;
    }

    private HttpRequest setHttpRequest(String jsonBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(modelUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private static long calculateExponentialBackoff(int attempt) {
        long backoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);

        long jitter = (long) (backoff * (RANDOM.nextDouble() * 0.4 - 0.2));
        return Math.max(100, backoff + jitter);
    }

    private static long parseRetryAfterHeader(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(header -> {
                    try {
                        return Long.parseLong(header) * 1000;
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }
}
