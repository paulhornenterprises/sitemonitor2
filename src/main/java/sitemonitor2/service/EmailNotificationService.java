package sitemonitor2.service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import sitemonitor2.jdbc.Site;

/**
 * Sends email notifications for monitored site status changes.
 *
 * <p>
 * Notification content is generated from a Thymeleaf HTML template and sent
 * as a MIME email containing both HTML and plain-text representations. The
 * plain-text representation provides a fallback for email clients that do not
 * support or permit HTML content.
 * </p>
 *
 * <p>
 * The {@code notify} field on the {@link Site} object is expected to contain
 * one or more comma-separated email addresses.
 * </p>
 */
@Slf4j
@Service
public class EmailNotificationService {

    private static final String EMAIL_TEMPLATE = "email/site-status-change";
    private static final String STATUS_OK = "OK";
    private static final DateTimeFormatter EVENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final String fromAddress;
    private final String applicationName;

    /**
     * Creates the email notification service.
     *
     * @param mailSender
     *     Spring mail sender used to create and send MIME messages
     *
     * @param templateEngine
     *     Thymeleaf template engine used to render the HTML email
     *
     * @param fromAddress
     *     configured From address for monitoring notifications
     *
     * @param applicationName
     *     display name used in notification subjects and sender information
     */
    public EmailNotificationService(
            JavaMailSender mailSender,
            SpringTemplateEngine templateEngine,
            @Value("${spring.mail.from:sitemonitor@localhost}")
            String fromAddress,
            @Value("${spring.application.name:SiteMonitor2}")
            String applicationName) {

        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromAddress = fromAddress;
        this.applicationName = applicationName;
    }

    /**
     * Sends an email notification when a monitored site changes status.
     *
     * <p>
     * Notifications are intended for the following state transitions:
     * </p>
     *
     * <ul>
     *   <li>{@code OK -> FAIL}</li>
     *   <li>{@code FAIL -> OK}</li>
     * </ul>
     *
     * <p>
     * No message is sent when the site, monitoring result, or recipient list
     * is absent. Delivery failures are logged but are not propagated to the
     * monitoring service, preventing an SMTP problem from interrupting the
     * remainder of a monitoring cycle.
     * </p>
     *
     * @param site
     *     site associated with the status change
     *
     * @param result
     *     monitoring result that triggered the notification
     */
    public void sendStatusChangeNotification(
            Site site,
            SiteCheckResult result) {

        if (!hasNotificationRecipients(site, result)) {
            return;
        }

		List<String> recipients = parseRecipients(site.getNotify());

        if (recipients.isEmpty()) {
            log.warn(
                    "Email notification skipped because no valid "
                            + "recipients were configured for site={}",
                    site.getName());

            return;
        }

		String subject = buildSubject(site, result);
		String plainTextBody = buildPlainTextBody(site, result);
		String htmlBody = renderHtmlBody(site, result);

        try {
			sendMessage(recipients, subject, plainTextBody, htmlBody);

            log.info(
                    "HTML notification sent for site={} "
                            + "status={} recipients={}",
                    site.getName(),
                    result.status(),
                    recipients);

        } catch (MessagingException | MailException exception) {
            log.error(
                    "Unable to send notification for site={} "
                            + "status={} recipients={}",
                    site.getName(),
                    result.status(),
                    recipients,
                    exception);
        }
    }

    /**
     * Determines whether sufficient information is available to construct
     * and send a notification.
     *
     * @param site
     *     monitored site
     *
     * @param result
     *     monitoring result
     *
     * @return
     *     {@code true} when a nonblank recipient list is available
     */
    private boolean hasNotificationRecipients(
            Site site,
            SiteCheckResult result) {

        return site != null
                && result != null
                && site.getNotify() != null
                && !site.getNotify().isBlank();
    }

    /**
     * Generates the HTML message body from the Thymeleaf email template.
     *
     * <p>
     * Template values are added to a dedicated Thymeleaf context. Values
     * rendered with {@code th:text} are HTML escaped by Thymeleaf.
     * </p>
     *
     * @param site
     *     monitored site
     *
     * @param result
     *     monitoring result
     *
     * @return
     *     rendered HTML email body
     */
    private String renderHtmlBody(
            Site site,
            SiteCheckResult result) {

		Context context = new Context(Locale.getDefault());
		context.setVariable("applicationName", applicationName);
		context.setVariable("siteName", displayValue(site.getName()));
		context.setVariable("siteUrl", displayValue(site.getUrl()));
		context.setVariable("status", displayValue(result.status()));
		context.setVariable("healthy", STATUS_OK.equalsIgnoreCase(result.status()));
		context.setVariable("eventChange", displayValue(result.eventChange()));
		context.setVariable("eventTime", formatEventTime(result));
		context.setVariable("responseTime", result.responseTime());
		context.setVariable("failures", result.failures());
		context.setVariable("eventDescription", displayValue(result.eventDescription()));

		return templateEngine.process(EMAIL_TEMPLATE, context);
    }

    /**
     * Creates and sends an email containing plain-text and HTML alternatives.
     *
     * @param recipients
     *     notification recipients
     *
     * @param subject
     *     message subject
     *
     * @param plainTextBody
     *     fallback plain-text message
     *
     * @param htmlBody
     *     rendered HTML message
     *
     * @throws MessagingException
     *     if the MIME message cannot be created
     *
     * @throws MailException
     *     if message delivery fails
     */
    private void sendMessage(
            List<String> recipients,
            String subject,
            String plainTextBody,
            String htmlBody) throws MessagingException {

		MimeMessage mimeMessage = mailSender.createMimeMessage();

        /*
         * Multipart mode is enabled because the email contains both
         * plain-text and HTML alternative representations.
         */
        MimeMessageHelper helper =
                new MimeMessageHelper(
                        mimeMessage,
                        true,
                        StandardCharsets.UTF_8.name());

		helper.setFrom(fromAddress); // , applicationName);
		helper.setTo(recipients.toArray(String[]::new));
        helper.setSubject(subject);

        /*
         * Spring creates a multipart/alternative message so clients can
         * choose either the plain-text or HTML representation.
         */
		helper.setText(plainTextBody, htmlBody);

        mailSender.send(mimeMessage);
    }

    /**
     * Builds the email subject for a site transition.
     *
     * @param site
     *     monitored site
     *
     * @param result
     *     monitoring result
     *
     * @return
     *     email subject
     */
    private String buildSubject(
            Site site,
            SiteCheckResult result) {

        String transitionDescription =
                STATUS_OK.equalsIgnoreCase(result.status())
                        ? "RECOVERED"
                        : "FAILED";

        return "%s - %s - %s"
                .formatted(
                        applicationName,
                        displayValue(site.getName()),
                        transitionDescription);
    }

    /**
     * Creates a plain-text alternative for clients unable to display HTML.
     *
     * @param site
     *     monitored site
     *
     * @param result
     *     monitoring result
     *
     * @return
     *     plain-text email content
     */
    private String buildPlainTextBody(
            Site site,
            SiteCheckResult result) {

        return """
                %s Site Status Change

                Site: %s
                URL: %s
                Status: %s
                Event Change: %s
                Event Time: %s
                Response Time: %d ms
                Consecutive Failures: %d

                Event Description:
                %s

                This message was generated automatically by %s.
                """
                .formatted(
                        applicationName,
                        displayValue(site.getName()),
                        displayValue(site.getUrl()),
                        displayValue(result.status()),
                        displayValue(result.eventChange()),
                        formatEventTime(result),
                        result.responseTime(),
                        result.failures(),
                        displayValue(result.eventDescription()),
                        applicationName);
    }

    /**
     * Formats the monitoring event time for display.
     *
     * @param result
     *     monitoring result
     *
     * @return
     *     formatted timestamp or {@code Not available}
     */
    private String formatEventTime(
            SiteCheckResult result) {

        if (result.eventTime() == null) {
            return "Not available";
        }

        return result.eventTime()
                .format(EVENT_TIME_FORMATTER);
    }

    /**
     * Parses a comma-separated list of notification recipients.
     *
     * @param notify
     *     comma-separated recipient list
     *
     * @return
     *     normalized list of nonblank recipient addresses
     */
    private List<String> parseRecipients(
            String notify) {

        return List.of(notify.split(","))
                .stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Converts null or blank display values into a readable fallback.
     *
     * @param value
     *     value to display
     *
     * @return
     *     original value or {@code Not available}
     */
    private String displayValue(
            String value) {

        return value == null || value.isBlank()
                ? "Not available"
                : value;
    }
}