package pl.dolien.climbcheck.stats;

/**
 * Live usage numbers for the public landing page — real counts from the database,
 * so the stats band on the site is honest instead of hardcoded.
 */
public record StatsResponse(long dashboards, long players, long lpPoints) {
}
