package com.minsu.welocation.tourapi;

public class GalleryReport {

    int checked;
    int withGallery;
    int photos;
    int failed;
    boolean alreadyRunning;
    String stoppedBecause;

    public int checked() {
        return checked;
    }

    public int withGallery() {
        return withGallery;
    }

    public int photos() {
        return photos;
    }

    public int failed() {
        return failed;
    }

    @Override
    public String toString() {
        return "checked=" + checked + " withGallery=" + withGallery + " photos=" + photos + " failed=" + failed
                + (stoppedBecause == null ? "" : " stopped=" + stoppedBecause)
                + (alreadyRunning ? " alreadyRunning" : "");
    }
}
