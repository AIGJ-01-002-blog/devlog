package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.team.blog.account.application.HandlePolicy;
import com.team.blog.account.application.HandleSuggester;
import com.team.blog.account.application.NicknamePolicy;
import com.team.blog.account.domain.AuthProvider;
import com.team.blog.support.IntegrationTest;

/** docs/08 §2·§3·§8, docs/09 §2~§5·§11 의 규칙 표를 그대로 테스트로 옮겼다. */
class HandleAndNicknameRulesTest extends IntegrationTest {
    @Autowired HandlePolicy handles;
    @Autowired HandleSuggester suggester;
    @Autowired NicknamePolicy nicknames;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "kim755030, OK", "go-kim755030, OK", "gi-kim_min, OK", "gokim, OK",
            "GOkim, INVALID_FORMAT", "Kim755030, INVALID_FORMAT", "kim-min, INVALID_FORMAT", "xx-kim, INVALID_FORMAT",
            "go-ki, INVALID_FORMAT", "ab, INVALID_FORMAT", "go_kim, PREFIX_LOOKALIKE", "gi_kim, PREFIX_LOOKALIKE", "ka_kim, PREFIX_LOOKALIKE", "ka-kim755030, OK", "fb_kim, PREFIX_LOOKALIKE", "fb-kim, OK", "nv_kim, PREFIX_LOOKALIKE", "nv-kim, OK",
            "_kim, INVALID_FORMAT", "kim_, INVALID_FORMAT", "go-_kim, INVALID_FORMAT",
            "gi-abcdefghijklmnopqrstu, INVALID_FORMAT", "gi-abcdefghijklmnopqrst, OK",
            "admin, RESERVED", "go-admin, RESERVED", "gi-devlog, RESERVED", "teamblog, RESERVED"
    })
    void 블로그_주소_형식_08_2(String handle, String expected) {
        HandlePolicy.Reason r = handles.checkFull(handle);
        assertThat(r == null ? "OK" : r.name()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}/{1} → {2}")
    @CsvSource({
            "GITHUB, octocat, octocat",
            "GITHUB, Kim-Min-Seo, kim_min_seo",
            "LOCAL, Kim.Min-Seo+blog, kim_min_seo",
            "LOCAL, _kim__min_, kim_min",
            "LOCAL, verylongemailaddress2026, verylongemailadd",
            "LOCAL, go.kim, gokim",
            "LOCAL, fb.kim, fbkim",
            "FACEBOOK, fb.kim, fb_kim",
            "LOCAL, nv.kim, nvkim",
            "NAVER, nv.kim, nv_kim",
            "GITHUB, a-very-long-github-login-name, a_very_long_gith"
    })
    void 미리_채우는_주소_본문_08_3(AuthProvider provider, String material, String expected) {
        assertThat(suggester.bodyFrom(provider, material)).isEqualTo(expected);
    }

    @Test
    void 영문이_없거나_3자_미만이면_user_6자리() {
        assertThat(suggester.bodyFrom(AuthProvider.LOCAL, "ab")).matches("user_\\d{6}");
        assertThat(suggester.bodyFrom(AuthProvider.GOOGLE, "김민서")).matches("user_\\d{6}");
    }

    @Test
    void 이미_있거나_예약어면_비어_있는_첫_번호() {
        String login = uniqueLogin("dup");
        jdbc.update("INSERT INTO member(handle, nickname) VALUES (?, ?)", "gi-" + login, "닉" + login.substring(login.length() - 6));
        assertThat(suggester.suggest(AuthProvider.GITHUB, login)).isEqualTo("gi-" + login + "_2");
        assertThat(suggester.suggest(AuthProvider.LOCAL, "admin")).isEqualTo("admin_2");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "김민서, OK", "Kim123, OK", "a1, OK",
            "ㅋㅋ, NICKNAME_INVALID_FORMAT", "김 민서, NICKNAME_INVALID_FORMAT", "김민서!, NICKNAME_INVALID_FORMAT",
            "😀😀, NICKNAME_INVALID_FORMAT", "가, NICKNAME_INVALID_FORMAT", "가나다라마바사아자차카, NICKNAME_INVALID_FORMAT",
            "12345, NICKNAME_LETTER_REQUIRED",
            "관리자김, NICKNAME_RESERVED", "admin123, NICKNAME_RESERVED", "Official, NICKNAME_RESERVED", "운영팀장, NICKNAME_RESERVED",
            "시1발왕, NICKNAME_BANNED_WORD", "sh1t, NICKNAME_BANNED_WORD", "병1신, NICKNAME_BANNED_WORD", "시발점, OK", "시발역사랑, OK"
    })
    void 닉네임_규칙_09(String raw, String expected) {
        NicknamePolicy.Code c = nicknames.checkRules(NicknamePolicy.normalize(raw));
        assertThat(c == null ? "OK" : c.name()).isEqualTo(expected);
    }

    @Test
    void 분리된_한글_NFD도_정상_닉네임() {
        String nfd = Normalizer.normalize("김민서", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("김민서");
        assertThat(NicknamePolicy.normalize(nfd)).isEqualTo("김민서");
        assertThat(nicknames.checkRules(NicknamePolicy.normalize(nfd))).isNull();
    }

    @Test
    void 금칙어_거부_메시지는_단어를_알려주지_않는다() {
        String message = NicknamePolicy.Code.NICKNAME_BANNED_WORD.message();
        assertThat(message).doesNotContain("시발").doesNotContain("shit");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "Kim Min-seo, KimMinseo", "'김민서 (Minseo)', 김민서Minseo", "Christopher Columbus, Christophe", "A, ''"
    })
    void 소셜_이름_미리_채우기_09_7(String social, String expected) {
        assertThat(nicknames.prefill(social)).isEqualTo(expected);
    }
}
