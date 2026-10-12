package com.team.blog.account.application;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.config.BlogProperties;

/**
 * 미리 채우는 블로그 주소 만들기 (docs/08 §3). 재료(이메일 @ 앞부분, GitHub은 아이디)를 정리해 16자로 자르고,
 * 접두어를 붙이고, 예약어이거나 이미 있으면 _2, _3 … 중 비어 있는 첫 번호를 붙인다.
 */
@Component
public class HandleSuggester {
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");
    /** 이메일 가입 주소가 소셜 접두어처럼 보이면(go_·gi_·ka_·fb_) 밑줄을 뺀다. HandlePolicy.PREFIX_LOOKALIKE와 같다 */
    private static final Pattern SOCIAL_LOOKALIKE = Pattern.compile("^(go|gi|ka|fb)_");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberRepository members;
    private final HandlePolicy policy;
    private final int autoMax;

    public HandleSuggester(MemberRepository members, HandlePolicy policy, BlogProperties props) {
        this.members = members;
        this.policy = policy;
        this.autoMax = props.handle().autoBodyMaxLength();
    }

    /** §3의 1~8단계: 재료 → 본문 (접두어·중복 처리 전). */
    public String bodyFrom(AuthProvider provider, String material) {
        String s = material == null ? "" : material;
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        s = s.toLowerCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        s = s.replaceAll("[^a-z0-9_]", "");
        s = MULTI_UNDERSCORE.matcher(s).replaceAll("_");
        s = trimUnderscore(s);
        if (s.length() > autoMax) s = trimUnderscore(s.substring(0, autoMax));
        if (provider == AuthProvider.LOCAL && SOCIAL_LOOKALIKE.matcher(s).lookingAt()) {
            s = s.substring(0, 2) + s.substring(3);
        }
        if (s.length() < 3) s = "user_" + String.format("%06d", RANDOM.nextInt(1_000_000));
        return s;
    }

    /** 사용할 수 있는 전체 주소를 제안한다 (§3의 9~10단계). */
    public String suggest(AuthProvider provider, String material) {
        return firstFree(provider, bodyFrom(provider, material));
    }

    /** 원하는 본문이 쓰였거나 예약어면 _2, _3 … 중 비어 있는 첫 값. 본문이 20자를 넘지 않게 자른다. */
    public String firstFree(AuthProvider provider, String body) {
        String prefix = provider.handlePrefix();
        String base = prefix + body;
        String likeBase = base.replace("!", "!!").replace("_", "!_").replace("%", "!%");
        Set<String> taken = new HashSet<>(members.findHandlesLike(base, likeBase + "!_%"));
        if (!taken.contains(base) && policy.checkBody(provider, body) == null) return base;
        for (int n = 2; n < 100_000; n++) {
            String suffix = "_" + n;
            String b = body.length() + suffix.length() > 20 ? trimUnderscore(body.substring(0, 20 - suffix.length())) : body;
            String candidate = prefix + b + suffix;
            if (!taken.contains(candidate) && policy.checkBody(provider, b + suffix) == null
                    && (b.equals(body) || !members.existsByHandle(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("주소를 제안할 수 없어요: " + base);
    }

    private static String trimUnderscore(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') start++;
        while (end > start && s.charAt(end - 1) == '_') end--;
        return s.substring(start, end);
    }

    List<String> takenFor(String base) {
        return members.findHandlesLike(base, base + "!_%");
    }
}
