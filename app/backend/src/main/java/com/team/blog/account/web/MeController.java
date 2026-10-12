package com.team.blog.account.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.application.AgreementService;
import com.team.blog.account.application.MemberSettingsService;
import com.team.blog.account.application.NicknameService;
import com.team.blog.account.application.ProfileService;
import com.team.blog.account.application.WithdrawalService;
import com.team.blog.account.domain.Visibility;
import com.team.blog.media.ProfileImages;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 내 프로필·설정 (005). 회원 번호를 요청으로 받지 않고 항상 로그인한 본인만 바꾼다 (FR-002). */
@RestController
@RequestMapping("/api/me")
public class MeController {
    private final NicknameService nicknameService;
    private final ProfileService profileService;
    private final ProfileImages profileImages;
    private final MemberSettingsService settings;
    private final AgreementService agreements;
    private final WithdrawalService withdrawals;

    public MeController(NicknameService nicknameService, ProfileService profileService, ProfileImages profileImages,
                        MemberSettingsService settings, AgreementService agreements, WithdrawalService withdrawals) {
        this.nicknameService = nicknameService;
        this.profileService = profileService;
        this.profileImages = profileImages;
        this.settings = settings;
        this.agreements = agreements;
        this.withdrawals = withdrawals;
    }

    /** 탈퇴 안내 숫자(020 FR-003)와, 탈퇴 신청한 계정이면 복구 기한(FR-016). 유예 중에도 열린다. */
    @GetMapping("/withdrawal")
    public WithdrawalService.Summary withdrawal(@CurrentMember MemberPrincipal me) {
        return withdrawals.summary(me.id());
    }

    public record WithdrawResult(java.time.Instant restoreBy) {}

    /** 탈퇴 신청 (020 FR-002·FR-009). 지금 기기를 포함해 모든 기기에서 로그아웃된다. */
    @PostMapping("/withdrawal")
    public WithdrawResult withdraw(@CurrentMember MemberPrincipal me, @RequestBody WithdrawalService.Request body,
                                   HttpServletRequest request) {
        java.time.Instant restoreBy = withdrawals.withdraw(me.id(), body);
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return new WithdrawResult(restoreBy);
    }

    /** [복구하기] (020 FR-017). */
    @PostMapping("/restore")
    public ResponseEntity<Void> restore(@CurrentMember MemberPrincipal me) {
        withdrawals.restore(me.id());
        return ResponseEntity.noContent().build();
    }

    public record NicknameRequest(String nickname) {}

    @PatchMapping("/nickname")
    public NicknameService.Result changeNickname(@CurrentMember MemberPrincipal me, @RequestBody NicknameRequest body) {
        return nicknameService.change(me.id(), body.nickname());
    }

    /** 설정 화면 전체 (FR-022·FR-023). 직전 로그인은 본인 세션에서만 나온다. */
    @GetMapping("/settings")
    public MemberSettingsService.Settings settings(@CurrentMember MemberPrincipal me) {
        return settings.of(me);
    }

    /**
     * 닉네임·소개·프로필 사진을 한 번에 저장한다 (FR-003). 보낸 칸만 바뀐다. profileImageId를 null로 보내면 기본 이미지.
     * 칸이 있는지(없음 vs null)를 구분해야 해서 Map으로 받는다.
     */
    @PatchMapping("/profile")
    public ProfileService.Profile updateProfile(@CurrentMember MemberPrincipal me, @RequestBody Map<String, Object> body) {
        return profileService.update(me.id(), new ProfileService.Patch(
                body.containsKey("nickname"), stringField(body, "nickname"),
                body.containsKey("bio"), stringField(body, "bio"),
                body.containsKey("profileImageId"), longField(body, "profileImageId")));
    }

    /** 프로필 사진 올리기 (FR-011~FR-013·FR-017). 본문은 사진 그대로(이미 256×256으로 자른 것), 최대 1MB. */
    @PostMapping("/profile-image")
    public ResponseEntity<ProfileImages.Uploaded> uploadProfileImage(@CurrentMember MemberPrincipal me, HttpServletRequest request)
            throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > ProfileImages.MAX_BYTES) throw tooLarge();
        byte[] data;
        try (InputStream in = request.getInputStream()) {
            data = in.readNBytes(ProfileImages.MAX_BYTES + 1);
        }
        if (data.length > ProfileImages.MAX_BYTES) throw tooLarge();
        if (data.length == 0) throw ApiException.badRequest("IMAGE_TYPE", "jpg·png·gif·webp 사진만 올릴 수 있어요.");
        return ResponseEntity.status(HttpStatus.CREATED).body(profileImages.upload(me.id(), data));
    }

    public record SettingsRequest(String defaultVisibility, Boolean lastActiveVisible, Boolean followListPublic) {}

    /**
     * 보낸 칸만 바꾼다: 새 글 기본 공개 범위 (005 FR-024, 이미 있는 글은 그대로), 최근 활동을 친구에게 보이기 (008 FR-012),
     * 팔로워·팔로잉 목록 공개 (079).
     */
    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@CurrentMember MemberPrincipal me, @RequestBody SettingsRequest body) {
        if (body.defaultVisibility() == null && body.lastActiveVisible() == null && body.followListPublic() == null) parseVisibility(null);
        Map<String, Object> changed = new java.util.LinkedHashMap<>();
        if (body.defaultVisibility() != null) {
            Visibility v = parseVisibility(body.defaultVisibility());
            settings.changeDefaultVisibility(me.id(), v);
            changed.put("defaultVisibility", v.name());
        }
        if (body.lastActiveVisible() != null) {
            settings.changeLastActiveVisible(me.id(), body.lastActiveVisible());
            changed.put("lastActiveVisible", body.lastActiveVisible());
        }
        if (body.followListPublic() != null) {
            settings.changeFollowListPublic(me.id(), body.followListPublic());
            changed.put("followListPublic", body.followListPublic());
        }
        return changed;
    }

    /** AI 동의 (018 FR-028·FR-030): 지금 문구 버전과 일자를 기록한다. 이미 동의했으면 버전·일자만 새로 쓴다. */
    @PostMapping("/agreements/ai")
    public ResponseEntity<Void> agreeAi(@CurrentMember MemberPrincipal me) {
        agreements.agreeAi(me.id());
        return ResponseEntity.noContent().build();
    }

    /** AI 동의 철회 (FR-025). 동의 기록이 없어도 같은 결과(204)다. */
    @DeleteMapping("/agreements/ai")
    public ResponseEntity<Void> withdrawAi(@CurrentMember MemberPrincipal me) {
        agreements.withdrawAi(me.id());
        return ResponseEntity.noContent().build();
    }

    private static Visibility parseVisibility(String raw) {
        if (raw != null) {
            try {
                return Visibility.valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 아래에서 오류
            }
        }
        throw ApiException.validation(List.of(new FieldErrorItem("defaultVisibility", "INVALID_VISIBILITY", "공개 범위를 골라 주세요.")));
    }

    private static String stringField(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (v == null || v instanceof String) return (String) v;
        throw ApiException.validation(List.of(new FieldErrorItem(name, "INVALID_TYPE", "글자로 보내 주세요.")));
    }

    private static Long longField(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (v == null) return null;
        if (v instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() > 0) return n.longValue();
        throw ApiException.validation(List.of(new FieldErrorItem(name, "PROFILE_IMAGE_INVALID", "이 사진은 프로필로 쓸 수 없어요. 다시 올려 주세요.")));
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "프로필 사진은 1MB까지 올릴 수 있어요.");
    }
}
