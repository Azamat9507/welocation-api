package com.minsu.welocation.spot;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SpotSpecs {

    private SpotSpecs() {
    }

    static Specification<Spot> matching(Region region, SpotCategory category, Activity activity, SpotTag tag,
                                        String text, boolean withoutScenes) {
        List<Specification<Spot>> specs = new ArrayList<>();
        if (withoutScenes) {
            specs.add((root, query, cb) -> {
                Subquery<Long> scene = query.subquery(Long.class);
                Root<SpotScene> s = scene.from(SpotScene.class);
                return cb.not(cb.exists(scene.select(s.get("id")).where(cb.equal(s.get("spot"), root))));
            });
        }
        if (region != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("region"), region));
        }
        if (category != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (activity != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("activity"), activity));
        }
        if (tag != null) {
            // one tag per filter, so the join can't multiply rows
            specs.add((root, query, cb) -> cb.equal(root.join("tags"), tag));
        }
        if (text != null) {
            // typed text is matched literally, so a lone "%" can't turn into "every spot".
            // The leading % rules out an index; a scan of a few thousand rows is fine for now.
            // A type name ("paintball", "картинг") also finds the spots of that type: Korean names rarely carry
            // the word the visitor types.
            String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            Set<Activity> named = Activity.named(text);
            specs.add((root, query, cb) -> {
                Predicate byText = cb.or(
                        cb.like(root.get("name"), pattern, '\\'),
                        cb.like(root.get("address"), pattern, '\\'));
                return named.isEmpty() ? byText : cb.or(byText, root.get("activity").in(named));
            });
        }
        return Specification.allOf(specs);
    }
}
