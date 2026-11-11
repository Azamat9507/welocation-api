package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotSource;
import com.minsu.welocation.tourapi.TourApiMapper.SpotData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

// Pulls the whitelisted categories from TourAPI and upserts them by contentid. It never deletes or
// hides spots: reviews point at spots without ON DELETE, and a place missing from one night's
// answer is not proof that it closed.
@Slf4j
@Service
public class TourApiSyncService {

    // Caps a bogus totalCount. Cafés need 30 pages at 100; 5 categories x 50 pages x 3 attempts
    // stays under the dev account's 1,000 requests a day.
    private static final int MAX_PAGES = 50;

    private final TourApiClient client;
    private final TourApiSpotRepository spots;
    private final TransactionTemplate tx;
    private final TourApiProperties props;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean warnedNoKey = new AtomicBoolean();

    TourApiSyncService(TourApiClient client, TourApiSpotRepository spots, TransactionTemplate tx,
                       TourApiProperties props) {
        this.client = client;
        this.spots = spots;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(cron = "${welocation.tourapi.cron}", zone = "Asia/Seoul")
    void nightly() {
        if (props.key() == null || props.key().isBlank()) {
            if (warnedNoKey.compareAndSet(false, true)) {
                log.warn("TOURAPI_KEY is not set; the nightly TourAPI sync does nothing");
            }
            return;
        }
        sync();
        syncGalleries(props.galleryPerNight());
    }

    public SyncReport sync() {
        SyncReport report = new SyncReport();
        if (!running.compareAndSet(false, true)) {
            log.warn("TourAPI sync is already running; this run is skipped");
            report.alreadyRunning = true;
            return report;
        }
        try {
            for (String code : TourApiMapper.CATEGORIES.keySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    report.failed.put(code, "not started, shutting down");
                    continue;
                }
                try {
                    syncCategory(code, report);
                } catch (RuntimeException e) {
                    // Pages already written stay; the next night picks up the rest. Only our own
                    // exception's message is known to be safe to log, others get their class name.
                    String reason = e instanceof TourApiException ? e.getMessage() : e.getClass().getSimpleName();
                    report.failed.put(code, reason);
                    log.warn("TourAPI sync of {} stopped: {}", code, reason);
                }
            }
            log.info("TourAPI sync finished: {}", report);
            return report;
        } finally {
            running.set(false);
        }
    }

    // Fetches photo galleries for spots that have none yet, at most `limit` spots (one request each).
    // The HTTP call happens outside any transaction; each spot is saved in its own short one.
    public GalleryReport syncGalleries(int limit) {
        GalleryReport report = new GalleryReport();
        if (limit <= 0) {
            return report;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("TourAPI sync is already running; the gallery run is skipped");
            report.alreadyRunning = true;
            return report;
        }
        try {
            List<Spot> batch = spots.findBySourceAndGallerySyncedAtIsNullAndThumbnailUrlIsNotNullOrderByIdAsc(
                    SpotSource.TOURAPI, Limit.of(limit));
            int failuresInARow = 0;
            for (Spot candidate : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    report.stoppedBecause = "shutting down";
                    break;
                }
                List<String> urls;
                try {
                    urls = TourApiMapper.galleryImages(client.detailImages(candidate.getExternalId()));
                    failuresInARow = 0;
                } catch (RuntimeException e) {
                    String reason = e instanceof TourApiException ? e.getMessage() : e.getClass().getSimpleName();
                    report.failed++;
                    log.warn("TourAPI gallery of spot {} failed: {}", candidate.getId(), reason);
                    if (e instanceof TourApiException t && !t.serviceDown()) {
                        // only this spot's answer was unusable: mark it done with no gallery, or it would
                        // sit at the head of the queue every night
                        urls = List.of();
                    } else {
                        // a used-up quota or a dead network fails every call the same way: stop instead of
                        // burning through the batch; these spots stay unmarked and are tried next night
                        if (++failuresInARow >= 3) {
                            report.stoppedBecause = reason;
                            break;
                        }
                        continue;
                    }
                }
                Long id = candidate.getId();
                List<String> found = urls;
                Integer count = tx.execute(status -> spots.findById(id)
                        .map(spot -> spot.replaceGallery(found, props.galleryMaxImages(), Instant.now()))
                        .orElse(null));
                report.checked++;
                if (count != null && count > 0) {
                    report.withGallery++;
                    report.photos += count;
                }
            }
            log.info("TourAPI gallery sync finished: {}", report);
            return report;
        } finally {
            running.set(false);
        }
    }

    private void syncCategory(String code, SyncReport report) {
        int pageNo = 1;
        int pages;
        do {
            TourApiClient.Page page = client.areaBasedList(code, pageNo);
            if (page.items().isEmpty()) {
                return;
            }
            tx.executeWithoutResult(status -> upsert(page.items(), report));
            pages = (page.totalCount() + props.pageSize() - 1) / props.pageSize();
            pageNo++;
        } while (pageNo <= Math.min(pages, MAX_PAGES));
    }

    private void upsert(List<JsonNode> items, SyncReport report) {
        Map<String, SpotData> incoming = new LinkedHashMap<>();
        for (JsonNode item : items) {
            TourApiMapper.Result result = TourApiMapper.map(item);
            if (result.data() == null) {
                report.skipped.merge(result.skipReason(), 1, Integer::sum);
            } else {
                incoming.put(result.data().externalId(), result.data());
            }
        }
        if (incoming.isEmpty()) {
            return;
        }
        Map<String, Spot> existing = spots.findBySourceAndExternalIdIn(SpotSource.TOURAPI, incoming.keySet())
                .stream().collect(Collectors.toMap(Spot::getExternalId, Function.identity()));

        for (SpotData d : incoming.values()) {
            Spot spot = existing.get(d.externalId());
            if (spot == null) {
                spot = new Spot(d.name(), d.category(), d.region(), d.address(), d.latitude(), d.longitude(),
                        SpotSource.TOURAPI, d.externalId());
                if (d.imageUrl() != null) {
                    spot.addImage(d.imageUrl());
                }
                spots.save(spot);
                report.created++;
            } else if (spot.updateFromSource(d.name(), d.category(), d.region(), d.address(),
                    d.latitude(), d.longitude(), d.imageUrl())) {
                report.updated++;
            } else {
                report.unchanged++;
            }
        }
    }
}
