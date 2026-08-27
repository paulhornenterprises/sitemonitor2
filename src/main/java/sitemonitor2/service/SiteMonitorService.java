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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import sitemonitor2.jdbc.Site;
import sitemonitor2.jdbc.SiteRepository;

@Service
public class SiteMonitorService {

	private static final Logger log = LoggerFactory.getLogger(SiteMonitorService.class);

	private static final String STATUS_OK = "OK";
	private static final String STATUS_FAIL = "FAIL";

	private static final String EVENT_CHANGE_YES = "YES";
	private static final String EVENT_CHANGE_NO = "NO";

	private static final int MAX_EVENT_DESCRIPTION_LENGTH = 1000;

	private final SiteRepository siteRepository;
	private final RestClient siteMonitorRestClient;
	private final Executor siteMonitorExecutor;

	/*
	 * Prevents a new monitoring cycle from starting while the prior cycle is still
	 * running.
	 */
	private final AtomicBoolean monitoringCycleRunning = new AtomicBoolean(false);

	public SiteMonitorService(SiteRepository siteRepository, RestClient siteMonitorRestClient,
			@Qualifier("siteMonitorExecutor") Executor siteMonitorExecutor) {

		this.siteRepository = siteRepository;
		this.siteMonitorRestClient = siteMonitorRestClient;
		this.siteMonitorExecutor = siteMonitorExecutor;
	}

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

	private List<Site> getEnabledSites() {

		List<Site> sites = new ArrayList<>();

		siteRepository.findByEnabledTrue().forEach(sites::add);

		return sites;
	}

	private List<CompletableFuture<SiteCheckResult>> submitSiteChecks(List<Site> sites) {

		return sites.stream().map(site -> CompletableFuture.supplyAsync(() -> checkSite(site), siteMonitorExecutor))
				.toList();
	}

	private List<SiteCheckResult> collectResults(List<CompletableFuture<SiteCheckResult>> futures) {

		return futures.stream().map(this::getCompletedResult).filter(Objects::nonNull).toList();
	}

	private SiteCheckResult getCompletedResult(CompletableFuture<SiteCheckResult> future) {

		try {
			return future.join();

		} catch (CompletionException exception) {
			log.error("Unable to retrieve a site monitoring result", exception.getCause());

			return null;
		}
	}

	private SiteCheckResult checkSite(Site site) {

		String previousStatus = normalizeStatus(site.getStatus());

		Instant requestStarted = Instant.now();

		try {
			return siteMonitorRestClient.get().uri(site.getUrl()).exchange((request, response) -> {

				LocalDateTime eventTime = LocalDateTime.now();

				long responseTime = Duration.between(requestStarted, Instant.now()).toMillis();

				HttpStatusCode httpStatus = response.getStatusCode();

				HttpHeaders responseHeaders = response.getHeaders();

				String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);

				boolean successfulHttpStatus = httpStatus.is2xxSuccessful();

				boolean assertTextFound = responseContainsAssertText(responseBody, site.getAssertText());

				String currentStatus = successfulHttpStatus && assertTextFound ? STATUS_OK : STATUS_FAIL;

				long failures = calculateFailureCount(site, currentStatus);

				String eventDescription = createEventDescription(httpStatus, responseHeaders, assertTextFound,
						site.getAssertText());

				String eventChange = determineEventChange(previousStatus, currentStatus);

				log.info(
						"Site check completed: id={}, " + "name={}, status={}, " + "responseTime={} ms, "
								+ "eventChange={}",
						site.getId(), site.getName(), currentStatus, responseTime, eventChange);

				return new SiteCheckResult(site.getId(), currentStatus, responseTime, failures, eventDescription,
						eventTime, eventChange);
			});

		} catch (Exception exception) {
			return createFailureResult(site, previousStatus, requestStarted, exception);
		}
	}

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

	private long calculateFailureCount(Site site, String currentStatus) {

		if (STATUS_FAIL.equals(currentStatus)) {
			return site.getFailures() + 1;
		}

		return 0;
	}

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
			updatedSites.add(site);
		}

		siteRepository.saveAll(updatedSites);
	}

	private void applyResult(Site site, SiteCheckResult result) {

		site.setStatus(result.status());
		site.setResponseTime(result.responseTime());
		site.setFailures(result.failures());
		site.setEventDescription(result.eventDescription());
		site.setEventTime(result.eventTime());
		site.setEventChange(result.eventChange());
		site.setLastChecked(result.eventTime());
	}

	private String truncate(String value) {

		if (value == null || value.length() <= MAX_EVENT_DESCRIPTION_LENGTH) {

			return value;
		}

		return value.substring(0, MAX_EVENT_DESCRIPTION_LENGTH);
	}
}