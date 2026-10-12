package com.team.blog.series.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;

/**
 * 포트폴리오 프로젝트 (072 3단계). 포트폴리오에 보이기로 한 시리즈가 프로젝트이고, 기간·한 줄 설명·기술·"우리 팀이 한 일"·"제 역할"을 적는다.
 * 프로젝트 글은 공개 글만 보인다(포트폴리오는 누구에게나 같은 화면).
 */
@Service
public class SeriesProjects {
    public static final int PERIOD_MAX = 40;
    public static final int SUMMARY_MAX = 200;
    public static final int TECH_MAX = 12;
    public static final int TECH_NAME_MAX = 30;
    public static final int TEXT_MAX = 2000;

    private final JdbcTemplate jdbc;

    public SeriesProjects(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 주인이 고치는 칸. 빈 칸은 null */
    public record Fields(boolean portfolio, String period, String summary, List<String> tech, String teamWork, String myRole) {}

    public record Item(long id, String title, String url, java.time.Instant firstPublicAt) {}

    public record Project(long id, String name, String slug, String url, String period, String summary, List<String> tech,
                          String teamWork, String myRole, List<Item> posts) {}

    public Fields get(long memberId, long seriesId) {
        return jdbc.query("""
                SELECT portfolio, project_period, project_summary, project_tech, project_team_work, project_my_role
                FROM series WHERE id = ? AND member_id = ?""", rs -> {
            if (!rs.next()) throw new NotFoundException();
            return new Fields(rs.getBoolean(1), rs.getString(2), rs.getString(3), techList(rs.getString(4)), rs.getString(5), rs.getString(6));
        }, seriesId, memberId);
    }

    public Fields save(long memberId, long seriesId, Fields in) {
        if (in == null) throw ApiException.badRequest("INVALID_REQUEST", "프로젝트 정보를 확인해 주세요.");
        String period = text(in.period(), PERIOD_MAX, "기간");
        String summary = text(in.summary(), SUMMARY_MAX, "한 줄 설명");
        String teamWork = text(in.teamWork(), TEXT_MAX, "우리 팀이 한 일");
        String myRole = text(in.myRole(), TEXT_MAX, "제 역할");
        Set<String> tech = new LinkedHashSet<>();
        for (String t : in.tech() == null ? List.<String>of() : in.tech()) {
            String v = t == null ? "" : t.strip().replaceAll("\\s+", " ").replace(",", "");
            if (v.isEmpty()) continue;
            if (v.codePointCount(0, v.length()) > TECH_NAME_MAX) {
                throw ApiException.badRequest("PROJECT_TECH", "기술 이름은 " + TECH_NAME_MAX + "자 안으로 써 주세요.");
            }
            tech.add(v);
        }
        if (tech.size() > TECH_MAX) throw ApiException.badRequest("PROJECT_TECH", "기술은 " + TECH_MAX + "개까지 적을 수 있어요.");
        int n = jdbc.update("""
                UPDATE series SET portfolio = ?, project_period = ?, project_summary = ?, project_tech = ?, project_team_work = ?,
                       project_my_role = ?, updated_at = now()
                WHERE id = ? AND member_id = ?""", in.portfolio(), period, summary, tech.isEmpty() ? null : String.join(",", tech),
                teamWork, myRole, seriesId, memberId);
        if (n == 0) throw new NotFoundException();
        return get(memberId, seriesId);
    }

    /** 포트폴리오 화면의 프로젝트. 최근 수정 순 */
    public List<Project> projects(long ownerId, String handle) {
        List<Project> out = new ArrayList<>();
        jdbc.query("""
                SELECT id, name, slug, project_period, project_summary, project_tech, project_team_work, project_my_role
                FROM series WHERE member_id = ? AND portfolio ORDER BY updated_at DESC, id DESC""",
                (RowCallbackHandler) rs -> out.add(new Project(rs.getLong(1), rs.getString(2), rs.getString(3), "/@" + handle + "/series/" + rs.getString(3),
                        rs.getString(4), rs.getString(5), techList(rs.getString(6)), rs.getString(7), rs.getString(8), new ArrayList<>())), ownerId);
        for (Project p : out) {
            p.posts().addAll(jdbc.query("""
                    SELECT p.id, p.title, p.first_public_at FROM series_post sp JOIN post p ON p.id = sp.post_id
                    JOIN member m ON m.id = p.author_id
                    WHERE sp.series_id = ? AND\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + " ORDER BY sp.position",
                    (rs, i) -> new Item(rs.getLong(1), rs.getString(2), "/@" + handle + "/posts/" + rs.getLong(1),
                            rs.getTimestamp(3).toInstant()), p.id()));
        }
        return out;
    }

    /** 글이 든 시리즈가 포트폴리오 프로젝트인지 (발행 창 안내) */
    public Optional<Boolean> postInProject(long postId) {
        return jdbc.query("SELECT s.portfolio FROM series_post sp JOIN series s ON s.id = sp.series_id WHERE sp.post_id = ?",
                rs -> rs.next() ? Optional.of(rs.getBoolean(1)) : Optional.<Boolean>empty(), postId);
    }

    private static String text(String raw, int max, String label) {
        if (raw == null) return null;
        String v = raw.strip();
        if (v.isEmpty()) return null;
        if (v.codePointCount(0, v.length()) > max) throw ApiException.badRequest("PROJECT_TEXT", label + "은(는) " + max + "자 안으로 써 주세요.");
        return v;
    }

    static List<String> techList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
