package com.team.blog.shorts.autoupload;

/**
 * 플랫폼 API 실패 (spec 108). 메시지는 스튜디오에 그대로 보이는 한국어다. 토큰·요청 주소를 싣지 않는다.
 * <ul>
 *   <li>AUTH: 토큰이 끊겼다. 다시 연결하기 전에는 다시 해도 안 된다</li>
 *   <li>RETRYABLE: 망·서버·한도 문제. 조금 뒤 다시 한다</li>
 *   <li>PERMANENT: 요청이 거절됐다(영상·문구 문제). 다시 해도 같다</li>
 * </ul>
 */
public class PlatformException extends RuntimeException {
    public enum Kind { AUTH, RETRYABLE, PERMANENT }

    private final Kind kind;

    public PlatformException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static PlatformException auth(String platformName) {
        return new PlatformException(Kind.AUTH, platformName + " 연결이 끊겼어요. 스튜디오의 자동 올리기에서 다시 연결해 주세요.");
    }

    public static PlatformException network(String platformName) {
        return new PlatformException(Kind.RETRYABLE, platformName + "에 연결하지 못했어요. 잠시 뒤 다시 해 볼게요.");
    }
}
