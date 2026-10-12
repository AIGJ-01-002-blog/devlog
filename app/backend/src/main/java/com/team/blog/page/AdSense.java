package com.team.blog.page;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구글 애드센스 (spec 076). 게시자 ID는 페이지 소스에 그대로 보이는 공개 값이라 설정에 둔다(비밀값 아님).
 * 비우면 광고 코드·ads.txt를 내지 않는다. 형식이 틀린 값은 HTML에 넣지 않도록 꺼진 것으로 본다.
 * 광고 자리는 코드로 박지 않고 애드센스 화면의 자동 광고로 조절한다.
 */
@Component
public class AdSense {
    private static final Pattern CLIENT_ID = Pattern.compile("ca-pub-\\d{10,20}");
    private static final String SCRIPT = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=";
    /** 구글 인증 기관 ID (애드센스 ads.txt 안내의 고정값) */
    private static final String GOOGLE_CERT_ID = "f08c47fec0942fa0";

    private final String client;

    public AdSense(@Value("${blog.adsense.client:}") String client) {
        String c = client == null ? "" : client.strip();
        this.client = CLIENT_ID.matcher(c).matches() ? c : "";
    }

    public boolean enabled() {
        return !client.isEmpty();
    }

    /** 공개 화면 head에 넣는 광고 코드. nonce가 있어야 엄격한 CSP('strict-dynamic')에서 실행된다 */
    public String scriptTag(String nonce) {
        return "<script async nonce=\"" + nonce + "\" src=\"" + SCRIPT + client + "\" crossorigin=\"anonymous\"></script>\n";
    }

    /** 사이트 루트 /ads.txt 한 줄 (IAB 형식: 광고 시스템, 게시자 ID, 관계, 인증 기관 ID) */
    public String adsTxt() {
        return "google.com, " + client.substring("ca-".length()) + ", DIRECT, " + GOOGLE_CERT_ID + "\n";
    }
}
