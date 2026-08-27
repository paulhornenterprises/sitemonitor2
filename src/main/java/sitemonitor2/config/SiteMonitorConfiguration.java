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

@Configuration
public class SiteMonitorConfiguration {

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