package com.team.blog.inquiry.application;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 처리 상태 (054). 접수 → 처리 중 → 해결(고친 버전이 있으면 함께) 또는 닫힘(답변만, 중복, 재현 안 됨). */
public enum InquiryStatus {
    RECEIVED("접수"), IN_PROGRESS("처리 중"), RESOLVED("해결"), CLOSED("닫힘");

    public static final Set<InquiryStatus> OPEN = Collections.unmodifiableSet(EnumSet.of(RECEIVED, IN_PROGRESS));

    private final String label;

    InquiryStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<InquiryStatus> parse(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
