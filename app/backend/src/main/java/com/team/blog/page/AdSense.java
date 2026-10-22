package com.team.blog.page;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구글 애드센스 (spec 076). 게시자 ID는 페이지 소스에 그대로 보이는 공개 값이라 설정에 둔다(비밀값 아님).
 * 형식이 틀린 값은 HTML에 넣지 않도록 없는 것으로 본다.
 * 광고 스위치 `ADS_PROVIDER`(spec 107)는 none(기본)|adsense다. none이면 광고 코드를 넣지 않고, 게시자 ID가 있으면
 * ads.txt와 소유 확인 메타만 둔다(재신청 때 다시 확인받지 않아도 되게). adsense일 때만 글 화면에 광고 코드를 싣는다.
 */
@Component
public class AdSense {
    private static final Pattern CLIENT_ID = Pattern.compile("ca-pub-\\d{10,20}");
    private static final Pattern SLOT_ID = Pattern.compile("\\d{6,20}");
    private static final String SCRIPT = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=";
    /** 구글 인증 기관 ID (애드센스 ads.txt 안내의 고정값) */
    private static final String GOOGLE_CERT_ID = "f08c47fec0942fa0";
    static final String PROVIDER_ADSENSE = "adsense";

    private final String client;
    private final boolean on;
    private final String slot;

    @Autowired
    public AdSense(@Value("${blog.adsense.client:}") String client, @Value("${blog.ads.provider:none}") String provider,
                   @Value("${blog.adsense.slot:}") String slot) {
        String c = client == null ? "" : client.strip();
        this.client = CLIENT_ID.matcher(c).matches() ? c : "";
        this.on = PROVIDER_ADSENSE.equalsIgnoreCase(provider == null ? "" : provider.strip()) && !this.client.isEmpty();
        String s = slot == null ? "" : slot.strip();
        this.slot = SLOT_ID.matcher(s).matches() ? s : "";
    }

    /** 광고를 켠 것과 같은 설정 (시험용): 광고 스위치 adsense, 광고 자리 없음 */
    AdSense(String client) {
        this(client, PROVIDER_ADSENSE, "");
    }

    /** 광고 코드를 싣는가: 스위치가 adsense이고 게시자 ID가 맞을 때만 */
    public boolean enabled() {
        return on;
    }

    /** 게시자 ID가 있는가. 광고를 꺼도 ads.txt·소유 확인 메타는 이 값으로 둔다 */
    public boolean configured() {
        return !client.isEmpty();
    }

    /** 공개 화면 head에 넣는 광고 코드. nonce가 있어야 엄격한 CSP('strict-dynamic')에서 실행된다 */
    public String scriptTag(String nonce) {
        return "<script async nonce=\"" + nonce + "\" src=\"" + SCRIPT + client + "\" crossorigin=\"anonymous\"></script>\n";
    }

    /**
     * 화면의 광고 자리(AdSlot)가 읽는 메타. 광고를 켜고 자리 ID(`ADSENSE_SLOT`)를 정했을 때만 낸다.
     * 메타가 없으면 화면은 자리를 그리지 않아 레이아웃이 밀리지 않는다(CLS 0).
     */
    public String slotMeta() {
        if (!on || slot.isEmpty()) return "";
        return "<meta name=\"devlog-ad-slot\" content=\"" + client + "/" + slot + "\">\n";
    }

    /** 광고를 싣지 않는 공개 화면에도 두는 사이트 소유 확인 메타 (spec 106) */
    public String accountMeta() {
        return "<meta name=\"google-adsense-account\" content=\"" + client + "\">\n";
    }

    /** 사이트 루트 /ads.txt 한 줄 (IAB 형식: 광고 시스템, 게시자 ID, 관계, 인증 기관 ID) */
    public String adsTxt() {
        return "google.com, " + client.substring("ca-".length()) + ", DIRECT, " + GOOGLE_CERT_ID + "\n";
    }
}
