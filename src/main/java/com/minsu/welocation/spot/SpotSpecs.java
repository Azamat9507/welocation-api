package com.minsu.welocation.spot;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class SpotSpecs {

    private SpotSpecs() {
    }

    static Specification<Spot> matching(Region region, SpotCategory category, SpotTag tag, String text) {
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
        if (text != null) {
            // typed text is matched literally, so a lone "%" can't turn into "every spot".
            // The leading % rules out an index; a scan of a few thousand rows is fine for now.
            String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(root.get("name"), pattern, '\\'),
                    cb.like(root.get("address"), pattern, '\\')));
        }
        return Specification.allOf(specs);
    }
}
