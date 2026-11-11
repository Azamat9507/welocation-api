package com.minsu.welocation.common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

public final class Constraints {

    private Constraints() {
    }

    // Only a hit on this unique key means "already exists"; a broken FK or CHECK must not be reported as a duplicate.
    // MySQL names the key as "<table>.<key>" in the error, hence endsWith.
    public static boolean isViolationOf(DataIntegrityViolationException e, String constraint) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase(Locale.ROOT).endsWith(constraint);
    }
}
