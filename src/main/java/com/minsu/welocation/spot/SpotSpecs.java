package com.minsu.welocation.spot;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class SpotSpecs {

    private SpotSpecs() {
    }

    static Specification<Spot> matching(Region region, SpotCategory category, SpotTag tag) {
        List<Specification<Spot>> specs = new ArrayList<>();
        if (region != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("region"), region));
        }
        if (category != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (tag != null) {
            // one tag per filter, so the join can't multiply rows
            specs.add((root, query, cb) -> cb.equal(root.join("tags"), tag));
        }
        return Specification.allOf(specs);
    }
}
