package sitemonitor2.config;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
	
	private static final String USER_AGENT = "SiteMonitor2/1.0";
	private static final String ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8";

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
			@Value("${site-monitor.read-timeout-seconds:15}") long readTimeoutSeconds,
			@Value("${site-monitor.ignore-ssl-errors:false}") boolean ignoreSslErrors,
			@Value("${site-monitor.max-connections:100}") int maxConnections,
			@Value("${site-monitor.max-connections-per-route:25}") int maxConnectionsPerRoute) {

		RequestConfig requestConfig =
		        RequestConfig.custom()
		                .build();
		
		Header userAgentHeader = new BasicHeader("User-Agent", USER_AGENT);
		Header acceptHeader = new BasicHeader("Accept", ACCEPT_HEADER);
		
		CloseableHttpClient client =
		        HttpClients.custom()
		                .setConnectionManager(buildConnectionManager(
		                		ignoreSslErrors, 
		                		connectTimeoutSeconds, 
		                		readTimeoutSeconds,
		                		maxConnections,
		                		maxConnectionsPerRoute))
		                .setDefaultRequestConfig(requestConfig)
		                .setDefaultCookieStore(new BasicCookieStore())
		                .setDefaultHeaders(
		                		List.of(
		                                userAgentHeader,
		                                acceptHeader))
		                .build();
		
		log.info(
			    "hostname verification disabled={}",
			    ignoreSslErrors);
		HttpComponentsClientHttpRequestFactory httpClientFactory = new HttpComponentsClientHttpRequestFactory();
		log.info(
			    "RestClient request factory={}",
			    httpClientFactory.getClass().getName());
		httpClientFactory.setHttpClient(client);		
		
		return RestClient.builder()
					.requestFactory(httpClientFactory)
					.build();
	}
	
	private PoolingHttpClientConnectionManager buildConnectionManager(
	        boolean ignoreSslErrors,
	        long connectTimeoutSeconds,
	        long readTimeoutSeconds,
	        int maxConnections,
	        int maxConnectionsPerRoute) {

	    ConnectionConfig connectionConfig =
	            ConnectionConfig.custom()
	                    .setConnectTimeout(
	                            Timeout.ofSeconds(connectTimeoutSeconds))
	                    .setSocketTimeout(
	                            Timeout.ofSeconds(readTimeoutSeconds))
	                    .setTimeToLive(
	                            TimeValue.ofMinutes(5))
	                    .build();

        PoolingHttpClientConnectionManagerBuilder builder =
	            PoolingHttpClientConnectionManagerBuilder
	                    .create()
	                    .setMaxConnTotal(maxConnections)
	                    .setMaxConnPerRoute(maxConnectionsPerRoute)
	                    .setDefaultConnectionConfig(connectionConfig);	    
	    
	    if (ignoreSslErrors) {
    	    log.warn("SSL certificate validation is DISABLED for the monitoring client.");

    	    TlsSocketStrategy tlsStrategy =
    	            ClientTlsStrategyBuilder.create()
    	                    .setSslContext(createTrustAllSslContext())
    	                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
    	                    .buildClassic();
    	    
	        builder.setTlsSocketStrategy(tlsStrategy);

	    }
	    
	    
	    return builder.build();
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
	
	private SSLContext createTrustAllSslContext() {

	    try {

	        TrustManager[] trustManagers =
	                new TrustManager[] {

	                        new X509TrustManager() {

	                            @Override
	                            public void checkClientTrusted(
	                                    X509Certificate[] chain,
	                                    String authType) {
	                            }

	                            @Override
	                            public void checkServerTrusted(
	                                    X509Certificate[] chain,
	                                    String authType) {
	                            }

	                            @Override
	                            public X509Certificate[]
	                                    getAcceptedIssuers() {

	                                return new X509Certificate[0];
	                            }
	                        }
	                };

	        SSLContext sslContext =
	                SSLContext.getInstance("TLS");

	        sslContext.init(
	                null,
	                trustManagers,
	                new SecureRandom());

	        return sslContext;

	    } catch (Exception exception) {

	        throw new RuntimeException(
	                "Unable to create trust-all SSL context",
	                exception);
	    }
	}	
}