package com.team.blog.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.config.BlogProperties;

/** 소셜 가입 닉네임 미리 채우기: 이미 쓰는 닉네임이면 숫자를 붙여 빈칸을 줄인다. */
class NicknamePrefillTest {
    final MemberRepository members = mock(MemberRepository.class);
    final NicknamePolicy policy = policy();

    NicknamePolicy policy() {
        BlogProperties props = mock(BlogProperties.class);
        when(props.nickname()).thenReturn(new BlogProperties.Nickname(Duration.ofDays(30), List.of("admin")));
        return new NicknamePolicy(mock(WordFilter.class), props, members);
    }

    void taken(String... nicknames) {
        for (String n : nicknames) when(members.existsNicknameIgnoreCase(eq(n), anyLong())).thenReturn(true);
    }

    @Test
    void freeNicknameIsUsedAsIs() {
        assertThat(policy.prefill("민서")).isEqualTo("민서");
    }

    @Test
    void takenNicknameGetsNextFreeNumber() {
        // 이미 가입한 회원이 같은 별명의 네이버 계정으로 오면 예전에는 빈칸이었다
        taken("민서", "민서2");
        assertThat(policy.prefill("민서")).isEqualTo("민서3");
    }

    @Test
    void numberFitsInTenCharacters() {
        taken("Christophe");
        assertThat(policy.prefill("Christopher Columbus")).isEqualTo("Christoph2");
    }

    @Test
    void givesUpAfterManyTakenAndLeavesBlank() {
        when(members.existsNicknameIgnoreCase(anyString(), anyLong())).thenReturn(true);
        assertThat(policy.prefill("민서")).isEmpty();
    }

    @Test
    void invalidOrReservedNameStaysBlank() {
        assertThat(policy.prefill("A")).isEmpty();
        assertThat(policy.prefill("admin")).isEmpty();
    }
}
