package com.minsu.welocation.tourapi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
class TourApiClient {

    // data.go.kr answers gateway errors (bad key, quota used up) with HTTP 200 and an XML body
    private static final Pattern XML_REASON = Pattern.compile("<returnReasonCode>\\s*(\\d+)\\s*<");

    private final TourApiProperties props;
    private final JsonMapper json;
    private final RestClient http;

    TourApiClient(TourApiProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    record Page(int totalCount, List<JsonNode> items) {
    }

    Page areaBasedList(String lclsSystm3, int pageNo) {
        String where = "areaBasedList2 " + lclsSystm3 + " page " + pageNo;
        URI uri = UriComponentsBuilder.fromUriString(props.baseUrl())
                .path("/areaBasedList2")
                .queryParam("serviceKey", "{key}")
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "WeLocation")
                .queryParam("_type", "json")
                .queryParam("arrange", "C")
                .queryParam("numOfRows", props.pageSize())
                .queryParam("pageNo", pageNo)
                .queryParam("lclsSystm1", lclsSystm3.substring(0, 2))
                .queryParam("lclsSystm2", lclsSystm3.substring(0, 4))
                .queryParam("lclsSystm3", lclsSystm3)
                // the key goes in as a variable so encode() escapes every reserved character in it;
                // left inline, a '+' in a base64 key would reach the server as a space
                .encode().buildAndExpand(requestKey(props.key())).toUri();

        String lastError = null;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                // decoded here: without a charset in Content-Type, body(String.class) falls back to ISO-8859-1
                byte[] body = http.get().uri(uri).retrieve().body(byte[].class);
                return parse(body == null ? null : new String(body, StandardCharsets.UTF_8), where);
            } catch (HttpServerErrorException e) {
                lastError = "HTTP " + e.getStatusCode().value();
            } catch (ResourceAccessException e) {
                lastError = "I/O " + rootCause(e).getClass().getSimpleName();
            } catch (HttpClientErrorException e) {
                throw fail(where + ": HTTP " + e.getStatusCode().value());
            } catch (RestClientException e) {
                // e.g. the body stalling past the read timeout after the headers arrived
                lastError = e.getClass().getSimpleName() + " (" + rootCause(e).getClass().getSimpleName() + ")";
            }
            log.warn("TourAPI {} attempt {}/{} failed: {}", where, attempt, props.maxAttempts(), lastError);
            if (attempt < props.maxAttempts()) {
                sleep(props.retryBackoff().multipliedBy(attempt).toMillis());
            }
        }
        throw fail(where + ": gave up after " + props.maxAttempts() + " attempts, last " + lastError);
    }

    private Page parse(String body, String where) {
        if (body == null || !body.stripLeading().startsWith("{")) {
            Matcher m = XML_REASON.matcher(body == null ? "" : body);
            throw fail(where + ": not a JSON answer" + (m.find() ? ", returnReasonCode " + m.group(1) : ""));
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw fail(where + ": broken JSON");
        }
        JsonNode response = root.path("response");
        String resultCode = response.path("header").path("resultCode").asString("");
        if (!"0000".equals(resultCode)) {
            // upstream text: no line breaks into our log, and kept short
            String msg = TourApiMapper.clean(response.path("header").path("resultMsg").asString(""), 100);
            throw fail(where + ": resultCode " + TourApiMapper.clean(resultCode, 10) + " " + msg);
        }
        JsonNode bodyNode = response.path("body");
        // an empty result comes back as "items": "" instead of an empty object
        JsonNode item = bodyNode.path("items").path("item");
        List<JsonNode> items = new ArrayList<>();
        if (item.isArray()) {
            item.values().forEach(items::add);
        } else if (item.isObject()) {
            items.add(item);
        }
        int total = bodyNode.path("totalCount").asInt(0);
        if (items.isEmpty() && total > 0 && where.endsWith(" page 1")) {
            throw fail(where + ": totalCount " + total + " but no items, response shape changed?");
        }
        return new Page(total, items);
    }

    // data.go.kr shows the key twice: raw ("Decoding") and percent-encoded ("Encoding"). Accept both.
    static String requestKey(String key) {
        if (key == null) {
            return "";
        }
        return key.contains("%") ? UriUtils.decode(key.strip(), StandardCharsets.UTF_8) : key.strip();
    }

    private TourApiException fail(String message) {
        String key = props.key();
        if (key != null && !key.isBlank()) {
            message = message.replace(key.strip(), "***").replace(requestKey(key), "***");
        }
        return new TourApiException(message);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TourApiException("interrupted while waiting to retry");
        }
    }
}
