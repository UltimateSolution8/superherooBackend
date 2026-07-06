package com.helpinminutes.api.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TranslationService {
  private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Map<String, String> cache = new ConcurrentHashMap<>();

  public TranslationService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1000))
        .build();
  }

  public String translate(String text, String targetLang) {
    if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
      return text;
    }
    
    // Support en, hi, te as target languages
    String lang = targetLang.toLowerCase().split("-")[0];
    if (!"en".equals(lang) && !"hi".equals(lang) && !"te".equals(lang)) {
      return text;
    }

    String cacheKey = text + "_" + lang;
    String cached = cache.get(cacheKey);
    if (cached != null) {
      return cached;
    }

    try {
      String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
      String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + lang + "&dt=t&q=" + encodedText;
      
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofMillis(1000))
          .header("User-Agent", "Mozilla/5.0")
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      
      if (response.statusCode() == 200) {
        JsonNode root = objectMapper.readTree(response.body());
        if (root.isArray() && root.size() > 0) {
          JsonNode segments = root.get(0);
          if (segments.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode segment : segments) {
              if (segment.isArray() && segment.size() > 0) {
                JsonNode translatedPart = segment.get(0);
                if (translatedPart != null && !translatedPart.isNull()) {
                  sb.append(translatedPart.asText());
                }
              }
            }
            String result = sb.toString();
            if (!result.isBlank()) {
              cache.put(cacheKey, result);
              return result;
            }
          }
        }
      } else {
        log.warn("Translation API returned status code: {}", response.statusCode());
      }
    } catch (Exception e) {
      log.warn("Failed to translate text '{}' to lang '{}' due to error: {}", text, lang, e.getMessage());
    }

    // Return original text if translation fails or timeouts
    return text;
  }
}
