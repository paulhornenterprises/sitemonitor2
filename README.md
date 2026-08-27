# SiteMonitor2

SiteMonitor2 is a lightweight website and endpoint monitoring application built using Spring Boot, Spring Data JDBC, Thymeleaf, Bootstrap, and an embedded H2 database.

The application allows administrators to define sites to monitor, configure response validation criteria, track failures, identify status changes, and capture monitoring events.

The system is designed to support concurrent monitoring of many endpoints while maintaining a simple deployment footprint.

---

# Features

## Site Administration

Administrators can:

- Create monitored site definitions
- Update site configuration
- Enable or disable monitoring
- Delete monitored sites
- Configure failure thresholds
- Configure notification recipients
- Configure response assertion text

---

## Site Monitoring

Each enabled site is periodically checked by the monitoring service.

The monitoring engine performs:

1. HTTP GET request
2. Response status validation
3. Response body validation
4. Response timing measurement
5. Failure tracking
6. Status change detection

---

## Dashboard

The monitoring dashboard displays:

- Site ID
- Name
- URL
- Current Status
- Enabled / Disabled
- Response Time
- Failure Count
- Assert Text
- Notification Recipients

Status is displayed using Bootstrap status badges:

- Green = OK
- Red = FAIL

---

## Event Tracking

Every monitoring execution records:

| Field | Purpose |
|---------|---------|
| status | Current monitor state |
| eventTime | Timestamp of result |
| eventDescription | Details of event |
| eventChange | YES/NO for status transition |
| lastChecked | Time monitor executed |
| responseTime | Request duration |
| failures | Consecutive failures |

---

# Monitoring Rules

## OK

When:

- HTTP status is 2xx
- Assert text exists in response body

## FAIL

When:

- HTTP status is not 2xx
- Assert text is missing from response body
- Timeout occurs
- Connection fails
- DNS resolution fails

---

# Status Change Detection

| Previous | Current | Event Change |
|-----------|-----------|-------------|
| OK | OK | NO |
| FAIL | FAIL | NO |
| OK | FAIL | YES |
| FAIL | OK | YES |

---

# Failure Counting

Failures accumulate while a site remains unavailable and automatically reset to zero when the site returns to an OK state.

---

# Architecture

## Technology Stack

| Component | Technology |
|------------|------------|
| Runtime | Java 17 |
| Framework | Spring Boot 4 |
| Data Access | Spring Data JDBC |
| Database | H2 |
| UI | Thymeleaf |
| Styling | Bootstrap 5 |
| Build | Gradle |
| Scheduling | Spring Scheduling |
| HTTP Client | Spring RestClient |

## High-Level Components

### SiteController

Handles CRUD operations and screen navigation.

### SiteRepository

Provides persistence operations for monitored sites.

### SiteMonitorService

Responsible for:

- Running scheduled checks
- Loading enabled sites
- Launching concurrent monitoring tasks
- Processing results
- Detecting state changes
- Persisting updates

### SiteMonitorConfiguration

Provides:

- Shared RestClient bean
- Dedicated monitoring executor thread pool

---

# Database

```sql
CREATE TABLE site (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    url VARCHAR(1000),
    status VARCHAR(30),
    response_time BIGINT,
    enabled BOOLEAN,
    assert_text VARCHAR(2000),
    failures BIGINT,
    failure_limit BIGINT,
    notify VARCHAR(500),
    last_notification VARCHAR(500),
    last_checked TIMESTAMP,
    event_time TIMESTAMP,
    event_description VARCHAR(1000),
    event_change VARCHAR(30)
);
```

---

# Concurrent Monitoring Design

Every monitoring cycle:

1. Loads enabled sites
2. Creates monitoring tasks
3. Submits work to a bounded executor
4. Executes HTTP requests concurrently
5. Collects results
6. Updates persisted site status information

Configuration:

```properties
site-monitor.concurrent-checks=32
site-monitor.queue-capacity=500
```

---

# Configuration

```properties
spring.datasource.url=jdbc:h2:file:./data/sitemonitor
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

site-monitor.interval-ms=60000
site-monitor.initial-delay-ms=5000
site-monitor.concurrent-checks=32
site-monitor.queue-capacity=500
site-monitor.connect-timeout-seconds=5
site-monitor.read-timeout-seconds=15
```

---

# User Interface

## Dashboard

Displays current monitor state and monitoring metrics.

## Site Maintenance

Allows editing:

- Name
- URL
- Enabled
- Assert Text
- Failure Limit
- Notify

Read-only monitor fields:

- Status
- Failure Count
- Event Details

---

# Running Locally

## Build

```bash
gradlew clean build
```

## Run

You must set the System property for the mail host on the JVM during startup or uncomment and set in the applications.properties.
 
-Dspring.mail.host=smtp.somehost.com
-Dspring.mail.from=someone@test.net

```bash
gradlew bootRun -Dspring.mail.host=smtp.somehost.com -Dspring.mail.from=someone@test.net
```

## Open Application

```text
http://localhost:8080/sites
```

## H2 Console

```text
http://localhost:8080/h2-console
```

---

# Future Enhancements

- Email notifications
- Recovery notifications
- Monitoring history table
- REST API
- Authentication and authorization
- Webhook notifications
- Historical trend reporting
- Site grouping
- Custom monitoring schedules

---

# Project Goals

SiteMonitor2 is intended to provide a simple but scalable monitoring platform capable of continuously validating application endpoints while maintaining:

- Minimal operational overhead
- Embedded persistence
- High monitoring throughput
- Clear event tracking
- Simple administration
- Lightweight deployment footprint
