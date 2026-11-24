package edu.smarttraffic.archiver.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.contracts.SensorId;
import edu.smarttraffic.contracts.TrafficReading;
import edu.smarttraffic.contracts.ZoneId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "TRAFFIC_ARCHIVER_PG_IT", matches = "true")
@SpringBootTest(classes = TrafficArchiverPostgresIntegrationTest.PersistenceTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TrafficArchiverPostgresIntegrationTest {
    @Autowired
    private JpaTrafficReadingArchiveAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironmentVariable("SPRING_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironmentVariable("SPRING_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironmentVariable("SPRING_DATASOURCE_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void archivesReadingAndIgnoresDuplicateEventId() {
        var eventId = "postgres-it-" + UUID.randomUUID();
        var occurredAt = Instant.parse("2026-11-11T12:00:00Z");
        adapter.archive(new TrafficReading(
                eventId, new SensorId("sensor-first"), new ZoneId("zone-first"), occurredAt, 12));
        adapter.archive(new TrafficReading(
                eventId,
                new SensorId("sensor-replay"),
                new ZoneId("zone-replay"),
                occurredAt.plusSeconds(60),
                99));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traffic_readings WHERE event_id = ?",
                Integer.class,
                eventId));
        assertEquals("sensor-first", jdbcTemplate.queryForObject(
                "SELECT sensor_id FROM traffic_readings WHERE event_id = ?",
                String.class,
                eventId));
        assertEquals(Integer.valueOf(12), jdbcTemplate.queryForObject(
                "SELECT vehicle_count FROM traffic_readings WHERE event_id = ?",
                Integer.class,
                eventId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traffic_reading_audit WHERE event_id = ?",
                Integer.class,
                eventId));
        assertEquals("INSERT", jdbcTemplate.queryForObject(
                "SELECT operation FROM traffic_reading_audit WHERE event_id = ?",
                String.class,
                eventId));
        assertEquals("sensor-first", jdbcTemplate.queryForObject(
                "SELECT sensor_id FROM traffic_reading_audit WHERE event_id = ?",
                String.class,
                eventId));
        assertEquals("zone-first", jdbcTemplate.queryForObject(
                "SELECT zone_id FROM traffic_reading_audit WHERE event_id = ?",
                String.class,
                eventId));
        assertEquals(Integer.valueOf(12), jdbcTemplate.queryForObject(
                "SELECT vehicle_count FROM traffic_reading_audit WHERE event_id = ?",
                Integer.class,
                eventId));
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT recorded_at IS NOT NULL FROM traffic_reading_audit WHERE event_id = ?",
                Boolean.class,
                eventId));
    }

    @Test
    void rejectsMutationOfArchivedReadingsAndAuditRecords() {
        var eventId = "postgres-it-" + UUID.randomUUID();
        adapter.archive(new TrafficReading(
                eventId,
                new SensorId("sensor-immutable"),
                new ZoneId("zone-immutable"),
                Instant.parse("2026-11-11T12:00:00Z"),
                7));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE traffic_readings SET vehicle_count = 8 WHERE event_id = ?",
                eventId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "DELETE FROM traffic_readings WHERE event_id = ?",
                eventId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE traffic_reading_audit SET operation = 'DELETE' WHERE event_id = ?",
                eventId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "DELETE FROM traffic_reading_audit WHERE event_id = ?",
                eventId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.execute(
                "TRUNCATE TABLE traffic_readings"));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.execute(
                "TRUNCATE TABLE traffic_reading_audit"));
    }

    private static String requiredEnvironmentVariable(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when TRAFFIC_ARCHIVER_PG_IT=true");
        }
        return value;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = TrafficReadingEntity.class)
    @EnableJpaRepositories(basePackageClasses = TrafficReadingJpaRepository.class)
    @Import(JpaTrafficReadingArchiveAdapter.class)
    static class PersistenceTestApplication {
    }
}
