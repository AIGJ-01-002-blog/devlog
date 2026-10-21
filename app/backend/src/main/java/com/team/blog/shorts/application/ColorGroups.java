package com.team.blog.shorts.application;

import java.util.List;

/**
 * 쇼츠 첫 화면 색을 세 편씩 같은 계열로 묶는다 (2026-10-21 블로그 주인 요청).
 * <p>
 * 채널 쇼츠 탭은 최신순 3열 격자라 한 줄에 세 편이 보인다. 편마다 색이 제각각이면 보기 불편하다는 의견을 받아,
 * 연달아 만든 세 편은 같은 룩(배경·강조색 계열)으로 그리고 세 편이 지나면 다른 계열로 넘어간다. 전환·카메라·배치·배경음은
 * 지금처럼 편마다 달라서 영상끼리는 여전히 다르게 보인다.
 * <p>
 * 순서는 밝은 색과 어두운 색을 번갈아 둬서 줄과 줄 사이가 또렷하게 갈린다. 이름은 렌더 워커 look.LOOKS와 같아야 한다.
 * 묶음은 {@link ShortsProperties#colorGroupsSince()} 뒤에 만든 영상부터 센다. 2026-10-21 채널에 9편(3의 배수)이 올라가 있어서
 * 첫 묶음부터 새 줄에 딱 맞는다. 만들고 올리지 않은 영상이 끼면 줄이 밀리니, 올리지 않을 영상은 지우고 다시 만든다. 매매 재현 영상은 워커가 늘 paper로 그리므로 세지 않는다.
 */
public final class ColorGroups {
    /** 세 편마다 바뀌는 룩 순서: 초록 → 남색 → 주황 → 바다 → 보라 → 검정 → 흰색, 다시 초록 */
    static final List<String> ORDER = List.of("mint", "night", "sunset", "ocean", "grape", "mono", "paper");
    /** 쇼츠 탭 한 줄의 칸 수 */
    static final int ROW = 3;

    private ColorGroups() {}

    /** slot번째(0부터) 영상의 룩 */
    public static String lookFor(long slot) {
        if (slot < 0) throw new IllegalArgumentException("slot: " + slot);
        return ORDER.get((int) ((slot / ROW) % ORDER.size()));
    }
}
