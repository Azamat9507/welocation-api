package com.minsu.welocation.spot;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.ConflictException;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.GeocodeHit;
import com.minsu.welocation.spot.SpotDtos.ManualSpotRequest;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

// Places an admin adds by hand (source MANUAL), for things TourAPI doesn't list, like paintball fields.
// The TourAPI sync only ever touches TOURAPI rows, so nothing here is overwritten at night.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManualSpotService {

    static final int MAX_PHOTOS = 10;
    private static final int MAX_GEOCODE_QUERY = 200;

    private final SpotRepository spotRepository;
    private final SpotService spotService;
    private final AdminGuard adminGuard;
    private final PhotoStore photos;
    private final Geocoder geocoder;

    public PageResponse<SpotSummary> list(PageParams params, CurrentUser me) {
        adminGuard.require(me);
        PageRequest pageable = PageRequest.of(params.pageOrDefault(), params.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "id"));
        return spotService.summaries(spotRepository.findBySource(SpotSource.MANUAL, pageable));
    }

    @Transactional
    public SpotDetail create(ManualSpotRequest request, CurrentUser me) {
        adminGuard.require(me);
        Spot spot = new Spot(clean(request.name()), request.category(), regionOf(request),
                clean(request.address()), request.latitude(), request.longitude(), SpotSource.MANUAL, null);
        spot.setActivity(activityOf(request));
        spot.describe(description(request));
        return SpotDetail.from(spotRepository.save(spot), SpotDtos.Rating.NONE);
    }

    @Transactional
    public SpotDetail update(long id, ManualSpotRequest request, CurrentUser me) {
        adminGuard.require(me);
        Spot spot = manual(id);
        spot.edit(clean(request.name()), request.category(), activityOf(request), regionOf(request),
                clean(request.address()), request.latitude(), request.longitude(), description(request));
        return spotService.get(spot.getId());
    }

    @Transactional
    public void delete(long id, CurrentUser me) {
        adminGuard.require(me);
        Spot spot = manual(id);
        if (spotRepository.hasVisitorData(id) != 0) {
            throw new ConflictException("spot has reviews, likes or bookmarks");
        }
        List<String> urls = spot.getImages().stream().map(SpotImage::getUrl).toList();
        spotRepository.delete(spot);
        afterCommit(() -> urls.forEach(photos::delete));
    }

    @Transactional
    public SpotDetail addPhoto(long id, byte[] bytes, CurrentUser me) {
        adminGuard.require(me);
        Spot spot = manual(id);
        if (spot.getImages().size() >= MAX_PHOTOS) {
            throw new BadRequestException("photo: at most " + MAX_PHOTOS + " photos");
        }
        String url = photos.save(bytes);
        // the file is written before the row; if the row doesn't make it, the file goes too
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    photos.delete(url);
                }
            }
        });
        spot.addImage(url);
        return spotService.get(spot.getId());
    }

    @Transactional
    public SpotDetail removePhoto(long id, String name, CurrentUser me) {
        adminGuard.require(me);
        Spot spot = manual(id);
        String url = PhotoStore.URL_PREFIX + name;
        if (!PhotoStore.isOurs(url) || !spot.removeImage(url)) {
            throw new NotFoundException("photo not found");
        }
        afterCommit(() -> photos.delete(url));
        return spotService.get(spot.getId());
    }

    public List<GeocodeHit> geocode(String query, CurrentUser me) {
        adminGuard.require(me);
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > MAX_GEOCODE_QUERY) {
            throw new BadRequestException("q: 1 to " + MAX_GEOCODE_QUERY + " characters");
        }
        return geocoder.find(q);
    }

    private Spot manual(long id) {
        Spot spot = spotRepository.findById(id).orElseThrow(() -> new NotFoundException("spot " + id + " not found"));
        if (spot.getSource() != SpotSource.MANUAL) {
            // TourAPI rows are rewritten every night; an edit here would be lost by morning
            throw new ConflictException("only spots added by hand can be changed here");
        }
        return spot;
    }

    private static Region regionOf(ManualSpotRequest request) {
        if (request.region() != null) {
            return request.region();
        }
        return Region.fromAddress(request.address())
                .orElseThrow(() -> new BadRequestException("region: pick one, the address doesn't say"));
    }

    // a type only says something for things to do
    private static Activity activityOf(ManualSpotRequest request) {
        return request.category() == SpotCategory.LEISURE ? request.activity() : null;
    }

    private static String description(ManualSpotRequest request) {
        String d = request.description();
        if (d == null || d.isBlank()) {
            return null;
        }
        // line breaks stay, the page shows them; other control and invisible characters go
        return d.replace("\r\n", "\n").replaceAll("[\\p{Cf}[\\p{Cc}&&[^\\n]]]", " ").strip();
    }

    private static String clean(String s) {
        String cleaned = s.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            throw new BadRequestException("name and address must have visible text");
        }
        return cleaned;
    }

    // the row is already gone; a file that won't delete is logged, not turned into an error for the admin
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("photo file not deleted: {}", e.getClass().getSimpleName());
                }
            }
        });
    }
}
