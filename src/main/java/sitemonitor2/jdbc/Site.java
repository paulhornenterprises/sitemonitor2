package sitemonitor2.jdbc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.format.annotation.DateTimeFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Table("SITE")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Site {

    @Id
    private Long id;
    private String name;
    private String url;
    private String status;
    private long responseTime;
    private boolean enabled;
    private String assertText;
	private long failures;
	private long failureLimit;
	private String notify;
	private String lastNotification;
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastChecked;

    //Change Event Collection
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private LocalDateTime eventTime;
	private String eventDescription;
	// YES when status changes, otherwise NO
	private String eventChange;

	public String getLastCheckedDisplay() {
		if (lastChecked == null) {
			return "";
		}
		return lastChecked.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}
	
	public String getEventTimeDisplay() {
		if (eventTime == null) {
			return "";
		}
		return eventTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}	
}
