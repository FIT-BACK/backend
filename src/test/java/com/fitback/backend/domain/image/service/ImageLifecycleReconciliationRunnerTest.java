package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ImageLifecycleReconciliationRunnerTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ImageLifecycleReconciliationRunner runner;

    @Test
    void reconcilesLegacyRowsWrittenAfterReleaseARollback() throws Exception {
        Member owner = Member.create(
                "release-a-rollback@fitback.com",
                "release-a-rollback",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(owner);
        entityManager.flush();

        jdbcTemplate.update(
                """
                INSERT INTO image (
                    image_id, owner_id, object_key, purpose, content_type, file_size,
                    status, visibility, presigned_expires_at, retry_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-after-rollback",
                owner.getId(),
                "prod/images/lookbook_matched/legacy-after-rollback.jpg",
                "LOOKBOOK_MATCHED",
                "image/jpeg",
                1024,
                "PENDING",
                "PRIVATE",
                Timestamp.from(Instant.parse("2026-07-30T00:05:00Z")),
                0,
                Timestamp.from(Instant.parse("2026-07-30T00:00:00Z"))
        );

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT purpose FROM image WHERE image_id = ?",
                String.class,
                "legacy-after-rollback"
        )).isEqualTo("LOOKBOOK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM image WHERE image_id = ?",
                String.class,
                "legacy-after-rollback"
        )).isEqualTo("PENDING_UPLOAD");
    }
}
