package com.minsu.welocation.spot;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Turns whatever an admin pastes (a Shorts link, a watch or youtu.be link, an embed link, or a bare id)
// into the 11-character video id. Only YouTube hosts are accepted, so nothing else can end up in the
// player's src.
final class YouTubeIds {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com");

    private YouTubeIds() {
    }

    static Optional<String> parse(String input) {
        String text = input == null ? "" : input.strip();
        if (ID.matcher(text).matches()) {
            return Optional.of(text);
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        String candidate = null;
        if (host.equals("youtu.be")) {
            candidate = firstSegment(path, "/");
        } else if (HOSTS.contains(host)) {
            if (path.equals("/watch")) {
                candidate = queryParam(uri.getRawQuery(), "v");
            } else {
                for (String prefix : new String[]{"/shorts/", "/embed/", "/live/"}) {
                    if (path.startsWith(prefix)) {
                        candidate = firstSegment(path, prefix);
                    }
                }
            }
        }
        return candidate != null && ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private static String firstSegment(String path, String prefix) {
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }
}
