package com.moyuan.buysense.run;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class PreferenceStore {
    public static final Preference DEFAULT = new Preference(true, "");

    private final JdbcTemplate jdbc;

    public PreferenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Preference find(String sessionId) {
        List<Preference> matches = jdbc.query("""
                        select personalization_enabled, preferred_brand
                        from user_preferences
                        where session_id = ?
                        """,
                (rs, rowNum) -> new Preference(
                        rs.getBoolean("personalization_enabled"),
                        rs.getString("preferred_brand")),
                sessionId);
        return matches.stream().findFirst().orElse(DEFAULT);
    }

    @Transactional
    public synchronized Preference save(String sessionId, Preference preference) {
        int updated = jdbc.update("""
                        update user_preferences
                        set personalization_enabled = ?, preferred_brand = ?, updated_at = ?
                        where session_id = ?
                        """,
                preference.personalizationEnabled(),
                preference.preferredBrand(),
                Timestamp.from(Instant.now()),
                sessionId);
        if (updated == 0) {
            jdbc.update("""
                            insert into user_preferences (
                                session_id, personalization_enabled, preferred_brand, updated_at
                            ) values (?, ?, ?, ?)
                            """,
                    sessionId,
                    preference.personalizationEnabled(),
                    preference.preferredBrand(),
                    Timestamp.from(Instant.now()));
        }
        return preference;
    }

    public record Preference(boolean personalizationEnabled, String preferredBrand) {
    }
}
