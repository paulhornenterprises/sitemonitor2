package sitemonitor2.jdbc;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
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
	@Column("RESPONSE_TIME")
    private long responseTime;
    private boolean enabled;
	@Column("ASSERT_TEXT")
    private String assertText;
	private long failures;
	@Column("FAILURE_LIMIT")
	private long failureLimit;
	private String notify;
	@Column("LAST_NOTIFICATION")
	private String lastNotification;
	@Column("LAST_CHECKED")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lastChecked;
    
    
    
}
