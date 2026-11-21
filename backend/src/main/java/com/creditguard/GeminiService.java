package com.creditguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

/** Thin wrapper around the Google Gemini REST API (generateContent). */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";

    private final RestClient rest = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.model}")
    private String model;

    /** Ask Gemini to read an image/PDF and answer as JSON. */
    public JsonNode askJson(String prompt, MultipartFile file) {
        String text = call(prompt, file, true);
        try {
            return mapper.readTree(stripFences(text));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Gemini did not return valid JSON. Try a clearer image/PDF.");
        }
    }

    /** Plain text answer (no file). */
    public String askText(String prompt) {
        return call(prompt, null, false).trim();
    }

    private String call(String prompt, MultipartFile file, boolean json) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("PASTE_")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GEMINI_API_KEY is not set. Put your key in backend/.env and restart.");
        }

        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));

        if (file != null) {
            String mime = file.getContentType();
            if (mime == null || !(mime.startsWith("image/") || mime.equals("application/pdf"))) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only image (jpg/png) or PDF files are allowed.");
            }
            try {
                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", mime,
                        "data", Base64.getEncoder().encodeToString(file.getBytes()))));
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the file.");
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(Map.of("parts", parts)));
        if (json) {
            body.put("generationConfig", Map.of("responseMimeType", "application/json"));
        }

        try {
            JsonNode res = rest.post()
                    .uri(URL, model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String text = res == null ? "" : res.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text").asText("");
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an empty answer.");
            }
            return text;
        } catch (RestClientResponseException e) {
            String msg = e.getResponseBodyAsString();
            log.error("Gemini error {}: {}", e.getStatusCode().value(), msg);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Gemini API error " + e.getStatusCode().value() + ": "
                            + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        }
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return t;
    }
}
