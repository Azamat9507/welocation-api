package com.minsu.welocation.spot;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.GeocodeHit;
import com.minsu.welocation.spot.SpotDtos.ManualSpotRequest;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ManualSpotController {

    private final ManualSpotService service;
    private final PhotoStore photos;

    // public, like every GET under /api/spots; the name is random and the file never changes
    @Operation(summary = "A photo an admin uploaded for a hand-added spot")
    @GetMapping("/api/spots/photos/{name}")
    public ResponseEntity<Resource> photo(@PathVariable String name) {
        return photos.find(name)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .<Resource>body(new FileSystemResource(file)))
                .orElseThrow(() -> new NotFoundException("photo not found"));
    }

    @Operation(summary = "Spots added by hand, newest first (admins only)", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/api/admin/spots")
    public PageResponse<SpotSummary> list(@Valid @ParameterObject @ModelAttribute PageParams params,
                                          @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return service.list(params, CurrentUser.from(auth));
    }

    @Operation(summary = "Add a spot by hand (admins only)", security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/api/admin/spots")
    @ResponseStatus(HttpStatus.CREATED)
    public SpotDetail create(@Valid @RequestBody ManualSpotRequest request,
                             @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return service.create(request, CurrentUser.from(auth));
    }

    @Operation(summary = "Change a spot added by hand (admins only)", security = @SecurityRequirement(name = "bearer"))
    @PutMapping("/api/admin/spots/{id}")
    public SpotDetail update(@PathVariable Long id, @Valid @RequestBody ManualSpotRequest request,
                             @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return service.update(id, request, CurrentUser.from(auth));
    }

    @Operation(summary = "Delete a spot added by hand that nobody reviewed, liked or saved (admins only)",
            security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/admin/spots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        service.delete(id, CurrentUser.from(auth));
    }

    @Operation(summary = "Upload a JPEG or PNG photo for a spot added by hand (admins only)",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping(value = "/api/admin/spots/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpotDetail addPhoto(@PathVariable Long id, @RequestPart("file") MultipartFile file,
                               @Parameter(hidden = true) JwtAuthenticationToken auth) {
        if (file.getSize() > PhotoStore.MAX_BYTES) {
            throw new BadRequestException("photo: empty or larger than 5 MB");
        }
        try {
            return service.addPhoto(id, file.getBytes(), CurrentUser.from(auth));
        } catch (IOException e) {
            throw new BadRequestException("photo: upload failed");
        }
    }

    @Operation(summary = "Remove a photo from a spot added by hand (admins only)", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/admin/spots/{id}/photos/{name}")
    public SpotDetail removePhoto(@PathVariable Long id, @PathVariable String name,
                                  @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return service.removePhoto(id, name, CurrentUser.from(auth));
    }

    @Operation(summary = "Coordinates for a Korean address, from OpenStreetMap (admins only)",
            security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/api/admin/geocode")
    public List<GeocodeHit> geocode(@RequestParam String q, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return service.geocode(q, CurrentUser.from(auth));
    }
}
