package com.team.blog.shorts.autoupload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (54) 탈퇴 정리 때 플랫폼 연결(암호화한 토큰)과 남은 올리기 작업을 지운다 (spec 108). 플랫폼에 올린 영상은 그쪽에 남는다 */
@Component
class AutoUploadWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final JdbcTemplate jdbc;

    AutoUploadWithdrawalPurgeStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 54;
    }

    @Override
    public void purge(long memberId) {
        jdbc.update("DELETE FROM shorts_auto_upload WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM shorts_platform_connection WHERE member_id = ?", memberId);
    }
}
