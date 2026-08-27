package sitemonitor2.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring configuration class responsible for creating and configuring
 * infrastructure components used by the site monitoring subsystem.
 *
 * <p>
 * This configuration provides:
 * </p>
 *
 * <ul>
 *   <li>
 *     A shared {@link RestClient} instance used for all outbound HTTP
 *     monitoring requests.
 *   </li>
 *   <li>
 *     A dedicated monitoring thread pool used to execute concurrent
 *     site availability and content validation checks.
 *   </li>
 * </ul>
 *
 * <p>
 * Separating these concerns into configuration-managed Spring beans
 * allows the monitoring service to focus solely on monitoring logic
 * while centralizing HTTP client and thread pool configuration.
 * </p>
 *
 * <p>
 * The monitoring executor is intentionally isolated from the application's
 * normal request processing threads to ensure large monitoring workloads
 * cannot negatively impact web request performance.
 * </p>
 */
@Slf4j
@Configuration
public class SiteMonitorConfiguration {

    /**
     * Creates the shared HTTP client used by the site monitoring service.
     *
     * <p>
     * The returned {@link RestClient} is reused across all monitoring
     * operations and is thread-safe for concurrent use.
     * Reusing a single instance avoids the overhead of repeatedly
     * constructing HTTP infrastructure for every monitored site.
     * </p>
     *
     * <p>
     * The client is configured with:
     * </p>
     *
     * <ul>
     *   <li>Connection timeout protection</li>
     *   <li>Read timeout protection</li>
     *   <li>Automatic HTTP redirect handling</li>
     *   <li>A consistent application User-Agent header</li>
     * </ul>
     *
     * <p>
     * These settings prevent slow or unreachable endpoints from blocking
     * monitoring threads indefinitely while still supporting common redirect
     * scenarios such as HTTP-to-HTTPS redirection.
     * </p>
     *
     * @param connectTimeoutSeconds
     *     Maximum number of seconds to wait while establishing a TCP
     *     connection to a monitored endpoint.
     *
     * @param readTimeoutSeconds
     *     Maximum number of seconds to wait for a response after a
     *     connection has been established.
     *
     * @return
     *     Shared {@link RestClient} instance used by all monitoring
     *     operations.
     */	
	@Bean
	public RestClient siteMonitorRestClient(

			@Value("${site-monitor.connect-timeout-seconds:5}") long connectTimeoutSeconds,

			@Value("${site-monitor.read-timeout-seconds:15}") long readTimeoutSeconds) {

		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
				.followRedirects(HttpClient.Redirect.NORMAL).build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

		requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

		return RestClient.builder().requestFactory(requestFactory).defaultHeader("User-Agent", "SiteMonitor2/1.0")
				.build();
	}

    /**
     * Creates the dedicated executor used by the monitoring subsystem
     * to perform concurrent site checks.
     *
     * <p>
     * Each monitored site check involves a potentially slow network
     * operation. Executing these checks sequentially would dramatically
     * reduce monitoring throughput and could cause monitoring cycles
     * to exceed their scheduled execution interval.
     * </p>
     *
     * <p>
     * This executor allows multiple sites to be monitored simultaneously
     * while still enforcing an upper bound on resource consumption.
     * </p>
     *
     * <p>
     * Configuration characteristics:
     * </p>
     *
     * <ul>
     *   <li>
     *     Fixed-size worker pool using the configured concurrency limit.
     *   </li>
     *   <li>
     *     Bounded queue to prevent unbounded memory growth.
     *   </li>
     *   <li>
     *     Graceful shutdown support.
     *   </li>
     *   <li>
     *     Backpressure via {@link ThreadPoolExecutor.CallerRunsPolicy}
     *     when capacity limits are reached.
     *   </li>
     * </ul>
     *
     * <p>
     * The {@code CallerRunsPolicy} was chosen intentionally because it
     * slows task submission when the system is overloaded instead of
     * silently dropping monitoring tasks.
     * </p>
     *
     * @param concurrentChecks
     *     Maximum number of monitoring tasks permitted to execute
     *     simultaneously.
     *
     * @param queueCapacity
     *     Maximum number of monitoring tasks waiting for execution
     *     before backpressure is applied.
     *
     * @return
     *     Dedicated monitoring {@link Executor} used by
     *     {@code SiteMonitorService}.
     */	
	@Bean("siteMonitorExecutor")
	public Executor siteMonitorExecutor(

			@Value("${site-monitor.concurrent-checks:32}") int concurrentChecks,

			@Value("${site-monitor.queue-capacity:500}") int queueCapacity) {

		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("site-monitor-");
		executor.setCorePoolSize(concurrentChecks);
		executor.setMaxPoolSize(concurrentChecks);
		executor.setQueueCapacity(queueCapacity);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();

		return executor;
	}
}