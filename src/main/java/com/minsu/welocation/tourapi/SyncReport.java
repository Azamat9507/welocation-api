package com.minsu.welocation.tourapi;

import java.util.Map;
import java.util.TreeMap;

public class SyncReport {

    int created;
    int updated;
    int unchanged;
    final Map<String, Integer> skipped = new TreeMap<>();
    final Map<String, String> failed = new TreeMap<>();
    boolean alreadyRunning;

    public int created() {
        return created;
    }

    public int updated() {
        return updated;
    }

    public int unchanged() {
        return unchanged;
    }

    public Map<String, Integer> skipped() {
        return skipped;
    }

    public Map<String, String> failed() {
        return failed;
    }

    public boolean alreadyRunning() {
        return alreadyRunning;
    }

    @Override
    public String toString() {
        return "created=" + created + " updated=" + updated + " unchanged=" + unchanged
                + " skipped=" + skipped + " failed=" + failed.keySet();
    }
}
