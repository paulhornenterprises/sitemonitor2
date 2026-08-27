package sitemonitor2.service;

import java.time.LocalDateTime;

/**
 * Immutable monitoring result produced by a single site health check.
 *
 * <p>
 * This record is used as a transport object between the parallel monitoring
 * workers and the persistence layer. Each instance represents the outcome
 * of exactly one monitoring execution against a single configured site.
 * </p>
 *
 * <p>
 * The monitoring service creates a {@code SiteCheckResult} after:
 * </p>
 *
 * <ul>
 *   <li>Executing an HTTP request against a monitored URL</li>
 *   <li>Validating the HTTP response status</li>
 *   <li>Validating the configured assertion text</li>
 *   <li>Calculating response time metrics</li>
 *   <li>Determining the site's resulting health status</li>
 *   <li>Detecting status transitions</li>
 * </ul>
 *
 * <p>
 * The resulting object is subsequently used to update the corresponding
 * {@link sitemonitor2.jdbc.Site} entity and trigger any downstream actions
 * such as alert notifications.
 * </p>
 *
 * @param siteId
 *     Unique identifier of the monitored site that produced this result.
 *
 * @param status
 *     Final monitoring status for the site.
 *
 *     Expected values:
 *
 *     <ul>
 *       <li>OK - Site passed all validation checks</li>
 *       <li>FAIL - Site failed one or more validation checks</li>
 *     </ul>
 *
 * @param responseTime
 *     Time required to complete the monitoring request,
 *     measured in milliseconds.
 *
 * @param failures
 *     Current consecutive failure count after applying
 *     this monitoring result.
 *
 * @param eventDescription
 *     Human-readable description of the monitoring result.
 *
 *     Typical contents include:
 *
 *     <ul>
 *       <li>HTTP status code</li>
 *       <li>Assertion failures</li>
 *       <li>Response header information</li>
 *       <li>Connection or timeout errors</li>
 *     </ul>
 *
 * @param eventTime
 *     Timestamp indicating when the monitoring event occurred.
 *
 * @param eventChange
 *     Indicates whether the site changed state as a result of
 *     the current monitoring cycle.
 *
 *     Expected values:
 *
 *     <ul>
 *       <li>YES - Status transitioned between OK and FAIL</li>
 *       <li>NO - Status remained unchanged</li>
 *     </ul>
 */
public record SiteCheckResult(
        Long siteId,
        String status,
        long responseTime,
        long failures,
        String eventDescription,
        LocalDateTime eventTime,
        String eventChange) {
}
