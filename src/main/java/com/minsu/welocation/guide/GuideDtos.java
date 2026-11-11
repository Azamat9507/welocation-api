package com.minsu.welocation.guide;

import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class GuideDtos {

    private GuideDtos() {
    }

    public record GuideRequest(@NotBlank @Size(max = 300) String question) {
    }

    // Every fact on a card (name, address, rating, coordinates) comes from the database; only answer and reason are
    // the model's words. route is null unless the question asked for one.
    public record GuideResponse(String answer, List<Pick> spots, List<RouteStop> route) {
    }

    public record Pick(SpotSummary spot, String reason) {
    }

    public record RouteStop(int order, SpotSummary spot, Integer metersFromPrevious) {
    }

    // the JSON the model has to answer with
    record ModelAnswer(String intro, List<ModelPick> picks, boolean wantsRoute) {
    }

    record ModelPick(long spotId, String reason) {
    }
}
