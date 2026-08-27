package sitemonitor2.service;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import sitemonitor2.jdbc.Site;

@Slf4j
@Service
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    
    @Value("${spring.mail.from:sitemonitor@localhost}")
    private String fromAddress;

	public EmailNotificationService(JavaMailSender mailSender) {
		this.mailSender = mailSender;
	}

    /**
     * Sends a notification when a monitored site's status changes.
     *
     * <p>
     * Notifications are intended for:
     * </p>
     *
     * <ul>
     *   <li>OK -> FAIL</li>
     *   <li>FAIL -> OK</li>
     * </ul>
     *
     * <p>
     * The notify field on the Site object is expected to contain a
     * comma-separated list of email addresses.
     * </p>
     *
     * @param site
     *     Site associated with the status change.
     *
     * @param result
     *     Monitoring result that triggered the notification.
     */
    public void sendStatusChangeNotification(
            Site site,
            SiteCheckResult result) {

        if (site == null
                || result == null
                || site.getNotify() == null
                || site.getNotify().isBlank()) {

            return;
        }

        List<String> recipients = parseRecipients(site.getNotify());

        if (recipients.isEmpty()) {
            return;
        }

        String subject =
                String.format(
                        "SiteMonitor2 - %s - %s",
                        site.getName(),
                        result.status());

        String body =
                """
                Site Status Change

                Site: %s
                URL: %s
                Status: %s
                Event Change: %s
                Event Time: %s
                Response Time: %s ms
                Consecutive Failures: %s
                Event Description: %s
                """
                .formatted(
                        site.getName(),
                        site.getUrl(),
                        result.status(),
                        result.eventChange(),
                        result.eventTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        result.responseTime(),
                        result.failures(),
                        result.eventDescription());

        try {

            SimpleMailMessage message = new SimpleMailMessage();
            
            message.setFrom(fromAddress);
			message.setTo(recipients.toArray(String[]::new));
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            log.info(
                    "Notification sent for site={} recipients={}",
                    site.getName(),
                    recipients);

        } catch (Exception exception) {

            log.error(
                    "Unable to send notification for site={}",
                    site.getName(),
                    exception);
        }
    }

    private List<String> parseRecipients(
            String notify) {

        return List.of(notify.split(","))
                .stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}