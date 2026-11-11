package com.minsu.welocation.tourapi;

// Never carries the original RestClient exception: its message holds the request URL, and the
// service key travels in that URL's query string.
class TourApiException extends RuntimeException {

    TourApiException(String message) {
        super(message, null, false, false);
    }
}
