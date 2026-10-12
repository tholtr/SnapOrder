package com.campusdash.it;

import com.campusdash.application.usecase.query.CursorPage;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 游标分页边界：多个任务 created_at 完全相同时，必须使用 id 作为稳定的第二排序键。
 */
@SpringBootTest(properties = {"dash.mq.enabled=false"})
class CursorPaginationSameTimestampIT {

    private static final long BASE_ID = 931_000_000_000L;
    private static final long CAMPUS_ID = 931L;
    private static final Timestamp SAME_CREATED_AT =
            Timestamp.valueOf("2026-09-04 00:00:00.000");

    @Autowired JdbcTemplate jdbc;
    @Autowired QueryErrandListUseCase queryUseCase;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void seed() {
        cleanup();
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    INSERT INTO errand (id, campus_id, publisher_id, type, title, reward_amount,
                                        slot_total, slot_taken, status, round, version, created_at)
                    VALUES (?, ?, 1001, 'DELIVERY', ?, 1000, 1, 0, 'PUBLISHED', 0, 0, ?)
                    """, BASE_ID + i, CAMPUS_ID, "same_time_" + i, SAME_CREATED_AT);
        }
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void sameCreatedAtUsesIdAsStableCursorTieBreaker() {
        CursorPage<Errand> first = queryUseCase.listByCursor(CAMPUS_ID, "PUBLISHED", null, 2);

        assertEquals(List.of(BASE_ID + 3, BASE_ID + 2),
                first.items().stream().map(Errand::id).toList());
        assertNotNull(first.nextCursor());

        CursorPage<Errand> second = queryUseCase.listByCursor(
                CAMPUS_ID, "PUBLISHED", first.nextCursor(), 2);

        assertEquals(List.of(BASE_ID + 1),
                second.items().stream().map(Errand::id).toList());
        assertNull(second.nextCursor());
    }

    private void cleanup() {
        jdbc.update("DELETE FROM errand WHERE id BETWEEN ? AND ?", BASE_ID + 1, BASE_ID + 3);
    }
}
