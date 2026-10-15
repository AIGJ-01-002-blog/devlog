package com.team.blog.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.domain.Member;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.media.ProfileImages;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.time.Times;

/**
 * 프로필 저장 (005 US1·US2, docs/11 §5). 닉네임·소개·프로필 사진을 한 번에 저장하고, 보낸 칸만 바꾼다.
 * 모든 칸을 먼저 검사해 하나라도 틀리면 아무것도 바꾸지 않고 틀린 칸을 모두 알려준다 (FR-003).
 * 회원 행을 잠그고 처리해 동시에 저장하면 나중 요청이 이긴다(FR-005). 사진은 회원당 한 장만 연결된다(FR-015).
 */
@Service
public class ProfileService {
    private static final String NICKNAME_FIELD = "nickname";

    private final MemberRepository members;
    private final NicknamePolicy nicknamePolicy;
    private final BioPolicy bioPolicy;
    private final ProfileImages profileImages;
    private final TransactionTemplate tx;
    private final BlogProperties props;
    private final Clock clock;

    public ProfileService(MemberRepository members, NicknamePolicy nicknamePolicy, BioPolicy bioPolicy,
                          ProfileImages profileImages, TransactionTemplate tx, BlogProperties props, Clock clock) {
        this.members = members;
        this.nicknamePolicy = nicknamePolicy;
        this.bioPolicy = bioPolicy;
        this.profileImages = profileImages;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 바꿀 칸. 각 *Set이 false면 그 칸은 그대로 둔다.
     * @param profileImageId null이면 기본 이미지로 되돌린다 (imageSet이 true일 때)
     */
    public record Patch(boolean nicknameSet, String nickname, boolean bioSet, String bio,
                        boolean imageSet, Long profileImageId) {}

    public record Profile(String nickname, Instant nicknameNextChangeableAt, String bio, String profileImageUrl) {}

    public Profile update(long memberId, Patch patch) {
        try {
            return tx.execute(s -> apply(memberId, patch));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(List.of(new FieldErrorItem(NICKNAME_FIELD, "NICKNAME_TAKEN", "방금 다른 분이 이 닉네임을 사용했어요.")));
        }
    }

    public Profile current(long memberId) {
        Member m = members.findById(memberId).orElseThrow();
        return view(m);
    }

    private Profile apply(long memberId, Patch patch) {
        Member m = members.findWithLockById(memberId).orElseThrow();
        Instant now = Times.now(clock);
        List<FieldErrorItem> errors = new ArrayList<>();
        Instant nextChangeableAt = null;

        String nickname = null;
        if (patch.nicknameSet()) {
            nickname = NicknamePolicy.normalize(patch.nickname());
            nextChangeableAt = checkNickname(m, memberId, nickname, now, errors);
        }

        String bio = null;
        if (patch.bioSet()) {
            bio = BioPolicy.normalize(patch.bio());
            BioPolicy.Code code = bioPolicy.check(bio);
            if (code != null) errors.add(new FieldErrorItem("bio", code.name(), code.message()));
        }

        if (patch.imageSet() && patch.profileImageId() != null && !profileImages.isAttachable(memberId, patch.profileImageId())) {
            errors.add(new FieldErrorItem("profileImageId", "PROFILE_IMAGE_INVALID", "이 사진은 프로필로 쓸 수 없어요. 다시 올려 주세요."));
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.", errors,
                    nextChangeableAt == null ? null : Map.of("nextChangeableAt", nextChangeableAt.toString()));
        }

        if (patch.nicknameSet()) m.changeNickname(nickname, now, props.nickname().changeCooldown());
        if (patch.bioSet() && !java.util.Objects.equals(emptyToNull(bio), m.getBio())) m.changeBio(emptyToNull(bio), now);
        if (patch.imageSet()) profileImages.attach(memberId, patch.profileImageId());
        members.flush();
        return view(m);
    }

    /** 바꿀 닉네임을 검사해 errors에 더한다. 아직 바꿀 수 없는 기간이면 다시 바꿀 수 있는 때를 돌려준다 */
    private Instant checkNickname(Member m, long memberId, String nickname, Instant now, List<FieldErrorItem> errors) {
        if (nickname.equals(m.getNickname())) return null;
        NicknamePolicy.Code code = nicknamePolicy.check(nickname, memberId);
        if (code != null) {
            errors.add(new FieldErrorItem(NICKNAME_FIELD, code == NicknamePolicy.Code.NICKNAME_DUPLICATE ? "NICKNAME_TAKEN" : code.name(),
                    code.message()));
            return null;
        }
        if (m.getNicknameChangedAt() != null && m.getNicknameChangedAt().plus(props.nickname().changeCooldown()).isAfter(now)) {
            errors.add(new FieldErrorItem(NICKNAME_FIELD, "NICKNAME_CHANGE_TOO_SOON", "닉네임은 바꾼 뒤 30일 동안 다시 바꿀 수 없어요."));
            return m.getNicknameChangedAt().plus(props.nickname().changeCooldown());
        }
        return null;
    }

    private Profile view(Member m) {
        Instant next = m.getNicknameChangedAt() == null ? null : m.getNicknameChangedAt().plus(props.nickname().changeCooldown());
        if (next != null && !next.isAfter(Times.now(clock))) next = null; // 이미 바꿀 수 있으면 알리지 않는다
        return new Profile(m.getNickname(), next, m.getBio(), profileImages.currentUrl(m.getId()).orElse(null));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
