package sitemonitor2.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;
import sitemonitor2.jdbc.Site;
import sitemonitor2.jdbc.SiteRepository;

/**
 * Scheduled monitoring service responsible for validating the health and
 * content of all enabled sites configured within the Site Monitor
 * application.
 *
 * <p>
 * The monitoring process executes on a fixed schedule and performs
 * concurrent HTTP requests against every enabled monitored site.
 * Validation consists of:
 * </p>
 *
 * <ol>
 *   <li>Making an HTTP GET request to the configured URL.</li>
 *   <li>Confirming the HTTP status code is successful (2xx).</li>
 *   <li>Confirming the configured assertion text exists within the
 *       response body.</li>
 * </ol>
 *
 * <p>
 * Monitoring results are persisted back to the Site table and update:
 * </p>
 *
 * <ul>
 *   <li>Status (OK / FAIL)</li>
 *   <li>Response time</li>
 *   <li>Failure count</li>
 *   <li>Event timestamp</li>
 *   <li>Event description</li>
 *   <li>Event change indicator</li>
 *   <li>Last checked timestamp</li>
 * </ul>
 *
 * <p>
 * Site checks are executed concurrently using a dedicated monitoring
 * executor to maximize throughput while preventing monitoring activity
 * from impacting application request processing threads.
 * </p>
 *
 * <p>
 * Only one monitoring cycle may run at a time. If a scheduled execution
 * occurs while a previous cycle is still in progress, the subsequent
 * execution is skipped.
 * </p>
 */
@Slf4j
@Service
public class SiteMonitorService {

	private static final String STATUS_OK = "OK";
	private static final String STATUS_FAIL = "FAIL";

	private static final String EVENT_CHANGE_YES = "YES";
	private static final String EVENT_CHANGE_NO = "NO";

	private static final int MAX_EVENT_DESCRIPTION_LENGTH = 1000;

	private final SiteRepository siteRepository;
	private final RestClient siteMonitorRestClient;
	private final Executor siteMonitorExecutor;
	private final EmailNotificationService emailNotificationService;

	/*
	 * Prevents a new monitoring cycle from starting while the prior cycle is still
	 * running.
	 */
	private final AtomicBoolean monitoringCycleRunning = new AtomicBoolean(false);

	/**
	 * Creates a new monitoring service.
	 *
	 * @param siteRepository
	 *     Repository used to retrieve and persist monitor configuration and
	 *     site health information.
	 *
	 * @param siteMonitorRestClient
	 *     Shared HTTP client used for outbound monitoring requests.
	 *
	 * @param siteMonitorExecutor
	 *     Dedicated executor used to perform concurrent site checks.
	 */	
	public SiteMonitorService(SiteRepository siteRepository, RestClient siteMonitorRestClient,
			@Qualifier("siteMonitorExecutor") Executor siteMonitorExecutor, 
			EmailNotificationService emailNotificationService) {

		this.siteRepository = siteRepository;
		this.siteMonitorRestClient = siteMonitorRestClient;
		this.siteMonitorExecutor = siteMonitorExecutor;
		this.emailNotificationService = emailNotificationService;
	}

	/**
	 * Executes a complete monitoring cycle.
	 *
	 * <p>
	 * This method is invoked automatically by Spring Scheduling according to
	 * the configured monitoring interval.
	 * </p>
	 *
	 * <p>
	 * Processing flow:
	 * </p>
	 *
	 * <ol>
	 *   <li>Prevents overlapping monitoring cycles.</li>
	 *   <li>Loads all enabled sites.</li>
	 *   <li>Submits monitoring tasks to the monitoring executor.</li>
	 *   <li>Waits for all monitoring tasks to complete.</li>
	 *   <li>Updates site status information.</li>
	 *   <li>Persists monitoring results.</li>
	 * </ol>
	 *
	 * <p>
	 * Any individual site failure does not stop the remainder of the
	 * monitoring cycle.
	 * </p>
	 */	
	@Scheduled(fixedRateString = "${site-monitor.interval-ms:60000}", initialDelayString = "${site-monitor.initial-delay-ms:5000}")
	public void monitorSites() {

		if (!monitoringCycleRunning.compareAndSet(false, true)) {
			log.warn("Skipping monitoring cycle because the previous " + "cycle is still running");
			return;
		}

		Instant cycleStarted = Instant.now();

		try {
			List<Site> sites = getEnabledSites();

			if (sites.isEmpty()) {
				log.debug("No enabled sites were found");
				return;
			}

			log.info("Starting monitoring cycle for {} enabled sites", sites.size());

			List<CompletableFuture<SiteCheckResult>> futures = submitSiteChecks(sites);
			
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
			
			List<SiteCheckResult> results = collectResults(futures);
			
			applyAndSaveResults(sites, results);

			long cycleDuration = Duration.between(cycleStarted, Instant.now()).toMillis();

			log.info("Completed monitoring cycle for {} sites in {} ms", results.size(), cycleDuration);

		} catch (CompletionException exception) {
			log.error("A monitoring task failed unexpectedly", exception.getCause());

		} catch (RuntimeException exception) {
			log.error("The site monitoring cycle failed unexpectedly", exception);

		} finally {
			monitoringCycleRunning.set(false);
		}
	}

	/**
	 * Retrieves all enabled sites that should participate in the current
	 * monitoring cycle.
	 *
	 * @return
	 *     List of enabled sites.
	 */	
	private List<Site> getEnabledSites() {

		List<Site> sites = new ArrayList<>();

		siteRepository.findByEnabledTrue().forEach(sites::add);

		return sites;
	}

	/**
	 * Submits monitoring tasks for every site using the configured monitoring
	 * executor.
	 *
	 * <p>
	 * Each site is checked independently and asynchronously using
	 * {@link CompletableFuture}.
	 * </p>
	 *
	 * @param sites
	 *     Sites to be monitored.
	 *
	 * @return
	 *     List of futures representing each monitoring operation.
	 */	
	private List<CompletableFuture<SiteCheckResult>> submitSiteChecks(List<Site> sites) {

		return sites.stream().map(site -> CompletableFuture.supplyAsync(() -> checkSite(site), siteMonitorExecutor))
				.toList();
	}

	/**
	 * Collects completed monitoring results while filtering failed
	 * futures that could not produce a valid monitoring result.
	 *
	 * @param futures
	 *     Completed monitoring futures.
	 *
	 * @return
	 *     Successfully completed monitoring results.
	 */	
	private List<SiteCheckResult> collectResults(List<CompletableFuture<SiteCheckResult>> futures) {

		return futures.stream().map(this::getCompletedResult).filter(Objects::nonNull).toList();
	}

	/**
	 * Extracts a monitoring result from a completed future.
	 *
	 * <p>
	 * Any asynchronous execution exceptions are logged and converted into
	 * a null result.
	 * </p>
	 *
	 * @param future
	 *     Monitoring future.
	 *
	 * @return
	 *     Completed monitoring result or null when retrieval fails.
	 */	
	private SiteCheckResult getCompletedResult(CompletableFuture<SiteCheckResult> future) {

		try {
			return future.join();

		} catch (CompletionException exception) {
			log.error("Unable to retrieve a site monitoring result", exception.getCause());

			return null;
		}
	}

	/**
	 * Performs a health check against a single monitored site.
	 *
	 * <p>
	 * A site is considered healthy when:
	 * </p>
	 *
	 * <ul>
	 *   <li>The HTTP response status is 2xx.</li>
	 *   <li>The configured assertion text is present within the response
	 *       body.</li>
	 * </ul>
	 *
	 * <p>
	 * The resulting status will be:
	 * </p>
	 *
	 * <ul>
	 *   <li>OK - validation successful</li>
	 *   <li>FAIL - validation unsuccessful</li>
	 * </ul>
	 *
	 * <p>
	 * Response timing metrics, event details, and change detection
	 * information are also collected.
	 * </p>
	 *
	 * @param site
	 *     Site being monitored.
	 *
	 * @return
	 *     Monitoring outcome for the site.
	 */	
	private SiteCheckResult checkSite(Site site) {

		String previousStatus = normalizeStatus(site.getStatus());

		Instant requestStarted = Instant.now();

		try {
			log.info("Checking site={} url={}",
				    site.getName(),
				    site.getUrl());
			
			return siteMonitorRestClient
					.get()
					.uri(site.getUrl())
					.exchange((request, response) -> {
				
				LocalDateTime eventTime = LocalDateTime.now();
				long responseTime = Duration.between(requestStarted, Instant.now()).toMillis();
				
				HttpStatusCode httpStatus = response.getStatusCode();
				HttpHeaders responseHeaders = response.getHeaders();
				String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
				
				boolean successfulHttpStatus = httpStatus.is2xxSuccessful();
				boolean assertTextFound = responseContainsAssertText(responseBody, site.getAssertText());
				String currentStatus = successfulHttpStatus && assertTextFound ? STATUS_OK : STATUS_FAIL;
				long failures = calculateFailureCount(site, currentStatus);

				String eventDescription = createEventDescription(
						httpStatus, 
						responseHeaders, 
						assertTextFound,
						site.getAssertText());

				String eventChange = determineEventChange(previousStatus, currentStatus);

				log.info("Site check completed: id={}, name={}, httpStatus={}, status={}, responseTime={} ms, eventChange={}",
						 site.getId(), 
						 site.getName(), 
						 httpStatus, 
						 currentStatus, 
						 responseTime, 
						 eventChange);

				return new SiteCheckResult(site.getId(), currentStatus, responseTime, failures, eventDescription,
						eventTime, eventChange);
			});

		} catch (Exception exception) {
			//log.error("Checking site " + site.getUrl() + " Exception.", exception);
			return createFailureResult(site, previousStatus, requestStarted, exception);
		}
	}

	/**
	 * Creates a monitoring result representing a technical failure during
	 * monitoring execution.
	 *
	 * <p>
	 * Examples include:
	 * </p>
	 *
	 * <ul>
	 *   <li>DNS resolution failures</li>
	 *   <li>Connection timeouts</li>
	 *   <li>TLS handshake failures</li>
	 *   <li>Connection refused errors</li>
	 * </ul>
	 *
	 * @param site
	 *     Site being monitored.
	 *
	 * @param previousStatus
	 *     Site status before the failed check.
	 *
	 * @param requestStarted
	 *     Request start time used for response-time calculation.
	 *
	 * @param exception
	 *     Failure encountered during monitoring.
	 *
	 * @return
	 *     Failure monitoring result.
	 */	
	private SiteCheckResult createFailureResult(Site site, String previousStatus, Instant requestStarted,
			Exception exception) {

		LocalDateTime eventTime = LocalDateTime.now();

		long responseTime = Duration.between(requestStarted, Instant.now()).toMillis();

		String exceptionMessage = exception.getClass().getSimpleName() + ": "
				+ Objects.toString(exception.getMessage(), "No exception message was provided");

		String eventChange = determineEventChange(previousStatus, STATUS_FAIL);

		log.warn("Site check failed: id={}, name={}, url={}, reason={}", site.getId(), site.getName(), site.getUrl(),
				exceptionMessage);

		return new SiteCheckResult(site.getId(), STATUS_FAIL, responseTime, site.getFailures() + 1,
				truncate(exceptionMessage), eventTime, eventChange);
	}

	/**
	 * Validates whether the response body contains the configured assertion
	 * text.
	 *
	 * <p>
	 * When no assertion text is configured, the response is automatically
	 * considered valid.
	 * </p>
	 *
	 * @param responseBody
	 *     HTTP response body.
	 *
	 * @param assertText
	 *     Expected text fragment.
	 *
	 * @return
	 *     True if validation succeeds.
	 */	
	private boolean responseContainsAssertText(String responseBody, String assertText) {

		/*
		 * If no assertion text is configured, a successful 2xx HTTP response is enough
		 * for an OK result.
		 */
		if (assertText == null || assertText.isBlank()) {
			return true;
		}

		return responseBody != null && responseBody.contains(assertText);
	}

	/**
	 * Calculates the next failure count value based on the monitoring
	 * outcome.
	 *
	 * <p>
	 * Failure counts increase when a site remains in FAIL status and are
	 * reset when the site returns to an OK state.
	 * </p>
	 *
	 * @param site
	 *     Current site state.
	 *
	 * @param currentStatus
	 *     Newly calculated status.
	 *
	 * @return
	 *     Updated failure count.
	 */	
	private long calculateFailureCount(Site site, String currentStatus) {

		if (STATUS_FAIL.equals(currentStatus)) {
			return site.getFailures() + 1;
		}

		return 0;
	}

	/**
	 * Creates a human-readable event description suitable for dashboard
	 * display and alerting.
	 *
	 * <p>
	 * Event descriptions contain:
	 * </p>
	 *
	 * <ul>
	 *   <li>HTTP response code</li>
	 *   <li>Assertion failures</li>
	 *   <li>Response header information</li>
	 * </ul>
	 *
	 * @return
	 *     Event description text.
	 */	
	private String createEventDescription(HttpStatusCode httpStatus, HttpHeaders responseHeaders,
			boolean assertTextFound, String assertText) {

		StringBuilder description = new StringBuilder();

		description.append("HTTP ").append(httpStatus.value());

		if (!assertTextFound) {
			description.append("; response did not contain assert text: ").append(assertText);
		}

		description.append("; response headers=").append(responseHeaders);

		return truncate(description.toString());
	}

	/**
	 * Determines whether the current monitoring cycle resulted in a status
	 * transition.
	 *
	 * <p>
	 * Event changes occur only when:
	 * </p>
	 *
	 * <ul>
	 *   <li>OK → FAIL</li>
	 *   <li>FAIL → OK</li>
	 * </ul>
	 *
	 * <p>
	 * First-time monitoring results do not generate an event change.
	 * </p>
	 *
	 * @param previousStatus
	 *     Previous site status.
	 *
	 * @param currentStatus
	 *     Newly calculated site status.
	 *
	 * @return
	 *     YES if a state transition occurred; otherwise NO.
	 */	
	private String determineEventChange(String previousStatus, String currentStatus) {

		boolean previousStatusCanBeCompared = STATUS_OK.equals(previousStatus) || STATUS_FAIL.equals(previousStatus);

		if (!previousStatusCanBeCompared) {
			return EVENT_CHANGE_NO;
		}

		return previousStatus.equals(currentStatus) ? EVENT_CHANGE_NO : EVENT_CHANGE_YES;
	}

	private String normalizeStatus(String status) {

		if (status == null) {
			return null;
		}

		return status.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Applies monitoring results to site entities and persists the updated
	 * values to the database.
	 *
	 * <p>
	 * Site lookups are performed using an in-memory map to avoid repeated
	 * linear scans when processing large numbers of monitored sites.
	 * </p>
	 *
	 * @param sites
	 *     Sites loaded at the beginning of the monitoring cycle.
	 *
	 * @param results
	 *     Completed monitoring results.
	 */	
	private void applyAndSaveResults(List<Site> sites, List<SiteCheckResult> results) {

		if (results.isEmpty()) {
			return;
		}

		Map<Long, Site> sitesById = sites.stream().collect(Collectors.toMap(Site::getId, Function.identity()));

		List<Site> updatedSites = new ArrayList<>(results.size());

		for (SiteCheckResult result : results) {
			Site site = sitesById.get(result.siteId());

			if (site == null) {
				log.warn("Site {} was not found while applying " + "its monitoring result", result.siteId());
				continue;
			}

			applyResult(site, result);

			if (shouldSendFailureNotification(site, result)) {
				emailNotificationService.sendStatusChangeNotification(site, result);
				site.setFailureAlertSent(true);
			}

			if (shouldSendRecoveryNotification(site, result)) {
				emailNotificationService.sendStatusChangeNotification(site, result);
				site.setFailureAlertSent(false);
			}
			
			updatedSites.add(site);
		}

		siteRepository.saveAll(updatedSites);
	}
	
	private boolean shouldSendFailureNotification(Site site, SiteCheckResult result) {
		return STATUS_FAIL.equals(result.status())
				&& result.failures() >= site.getFailureLimit()
				&& !site.isFailureAlertSent();
	}

	private boolean shouldSendRecoveryNotification(Site site, SiteCheckResult result) {
		return STATUS_OK.equals(result.status()) 
				&& EVENT_CHANGE_YES.equals(result.eventChange())
				&& site.isFailureAlertSent();
	}
	
	/**
	 * Copies monitoring result values to a Site entity prior to persistence.
	 *
	 * @param site
	 *     Site entity being updated.
	 *
	 * @param result
	 *     Monitoring result to apply.
	 */	
	private void applyResult(Site site, SiteCheckResult result) {

		site.setStatus(result.status());
		site.setResponseTime(result.responseTime());
		site.setFailures(result.failures());
		site.setEventDescription(result.eventDescription());
		site.setEventTime(result.eventTime());
		site.setEventChange(result.eventChange());
		site.setLastChecked(result.eventTime());
	}

	/*** * Ensures event descriptions fit within the configured database column
	 * length.
	 *
	 * @param value
	 *    Text to be truncated.
	 *
	 * @return
	 *     Original or truncated text depending on length.
	 */	
	private String truncate(String value) {

		if (value == null || value.length() <= MAX_EVENT_DESCRIPTION_LENGTH) {

			return value;
		}

		return value.substring(0, MAX_EVENT_DESCRIPTION_LENGTH);
	}

}