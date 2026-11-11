package com.minsu.welocation.tourapi;

// Never carries the original RestClient exception: its message holds the request URL, and the
// service key travels in that URL's query string.
class TourApiException extends RuntimeException {

    // true when the whole service is the problem (network, gateway, key, quota), so retrying other
    // items right away is pointless; false when only this one answer was unusable
    private final boolean serviceDown;

    TourApiException(String message) {
        this(message, true);
    }

    TourApiException(String message, boolean serviceDown) {
        super(message, null, false, false);
        this.serviceDown = serviceDown;
    }

    boolean serviceDown() {
        return serviceDown;
    }
}
