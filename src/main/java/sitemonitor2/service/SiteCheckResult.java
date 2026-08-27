package sitemonitor2.service;

import java.time.LocalDateTime;

@SuppressWarnings("preview")
public record SiteCheckResult(
        Long siteId,
        String status,
        long responseTime,
        long failures,
        String eventDescription,
        LocalDateTime eventTime,
        String eventChange) {
}
