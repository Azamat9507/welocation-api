package com.minsu.welocation.spot;

import com.minsu.welocation.common.ServiceUnavailableException;
import com.minsu.welocation.spot.SpotDtos.GeocodeHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// Address -> coordinates for the admin form, from OpenStreetMap's Nominatim. Its usage policy asks for at most one
// request a second and a User-Agent that names the app; only admins call this, a few times when adding a place.
// The result is usually the street, not the door, so the form shows it on a map link before saving.
@Slf4j
@Component
class Geocoder {

    private static final int MAX_HITS = 5;
    private static final long MIN_GAP_MS = 1100;

    private final RestClient http;
    private final JsonMapper json;
    private final String baseUrl;
    private long lastCall;

    Geocoder(@Value("${welocation.geocode.base-url}") String baseUrl, JsonMapper json) {
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "WeLocation/1.0 (admin place form; welocation.178-128-219-123.sslip.io)")
                .build();
        this.baseUrl = baseUrl;
    }

    List<GeocodeHit> find(String address) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).path("/search")
                .queryParam("q", address)
                .queryParam("format", "jsonv2")
                .queryParam("countrycodes", "kr")
                .queryParam("accept-language", "ko")
                .queryParam("limit", MAX_HITS)
                .encode().build().toUri();
        JsonNode body;
        try {
            waitTurn();
            byte[] raw = http.get().uri(uri).retrieve().body(byte[].class);
            body = raw == null ? null : json.readTree(raw);
        } catch (RestClientException | JacksonException e) {
            log.warn("geocoding failed: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("geocoding is not available right now");
        }
        List<GeocodeHit> hits = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode item : body) {
                double lat = number(item.path("lat").asString(""));
                double lng = number(item.path("lon").asString(""));
                if (lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132.5 && hits.size() < MAX_HITS) {
                    String label = item.path("display_name").asString("").strip();
                    hits.add(new GeocodeHit(lat, lng, label.length() > 200 ? label.substring(0, 200) : label));
                }
            }
        }
        return hits;
    }

    private synchronized void waitTurn() {
        long wait = lastCall + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException("interrupted");
            }
        }
        lastCall = System.currentTimeMillis();
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
