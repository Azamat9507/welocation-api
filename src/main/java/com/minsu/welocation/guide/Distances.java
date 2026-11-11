package com.minsu.welocation.guide;

final class Distances {

    // the sphere MySQL's ST_Distance_Sphere uses, so distances here match the rest of the API
    static final double EARTH_RADIUS_METERS = 6_370_986;

    private Distances() {
    }

    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }
}
