package com.team.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.team.blog.support.Containers;

/**
 * 공용 DB 조건에서 마이그레이션이 끝까지 도는지 (2026-10-07 인프라 결정):
 * 정해진 스키마(currentSchema)만 쓰고, 확장 생성 권한이 없어 trgm 인덱스를 못 만들어도 실패하지 않는다.
 */
class MigrationTest {
    @Test
    void 확장_권한_없는_전용_스키마에서도_마이그레이션이_끝난다() throws Exception {
        String admin = Containers.POSTGRES.getJdbcUrl();
        try (Connection c = DriverManager.getConnection(admin, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS cf_test CASCADE");
            st.execute("DROP ROLE IF EXISTS cf_user");
            st.execute("CREATE ROLE cf_user LOGIN PASSWORD 'cf_pw' NOSUPERUSER NOCREATEDB");
            st.execute("CREATE SCHEMA cf_test AUTHORIZATION cf_user");
        }
        String url = admin + (admin.contains("?") ? "&" : "?") + "currentSchema=cf_test";
        Flyway.configure().dataSource(url, "cf_user", "cf_pw").defaultSchema("cf_test").schemas("cf_test")
                .createSchemas(false).locations("classpath:db/migration").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "cf_user", "cf_pw"); Statement st = c.createStatement()) {
            // V45 뒤: 테이블 66개(V3 정규화 28개 + member_telegram + series·series_post + member_about + member_social_link + post_thumbnail + oauth_client·personal_access_token·post_ai_hint + inquiry·notification_inquiry + post_revision + ai_post_proposal·ai_note + site_visit + member_active_day + visit_source_day·page_view_day + notification_ai_proposal + post_topic + series_subscription·post_topic_optout + member_discord + shorts·shorts_render_request + shorts_upload + trade·trade_day + shorts_batch + point_ledger·point_attendance·point_rule·point_badge + shop_item·member_item·member_decoration + paper_position·paper_order) + 통계 뷰 post_stat 1개. V16 post_embedding은 확장 권한이 없는 이 계정에서는 건너뛴다. Crowfoot 문서 660은 아직 28개
            ResultSet tables = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'cf_test' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'");
            tables.next();
            assertThat(tables.getInt(1)).isEqualTo(66);
            ResultSet views = st.executeQuery("SELECT string_agg(table_name, ',') FROM information_schema.views WHERE table_schema = 'cf_test'");
            views.next();
            assertThat(views.getString(1)).isEqualTo("post_stat");
            ResultSet trgm = st.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = 'cf_test' AND indexname LIKE '%_trgm'");
            trgm.next();
            assertThat(trgm.getInt(1)).isZero();
            ResultSet search = st.executeQuery("SELECT count(*) FROM post WHERE title LIKE '%x%'");
            search.next();
            assertThat(search.getInt(1)).isZero();
        }
    }
}
