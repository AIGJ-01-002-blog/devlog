package com.team.blog.support;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 쿠키를 기억하는 테스트용 브라우저. 세션은 Spring Session(Redis)에 있으므로 MockHttpSession이 아니라
 * 세션 쿠키로 이어 간다. 로그인 때 세션 ID가 바뀌면 새 쿠키로 갈아 끼운다.
 */
public class Browser {
    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private String remoteAddr = "198.51.100.10";

    public Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Browser from(String ip) {
        this.remoteAddr = ip;
        return this;
    }

    public ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
        if (!cookies.isEmpty()) builder.cookie(cookies.values().toArray(Cookie[]::new));
        String ip = remoteAddr;
        builder.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        ResultActions actions = mvc.perform(builder);
        MvcResult result = actions.andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getMaxAge() == 0 || c.getValue() == null || c.getValue().isEmpty()) cookies.remove(c.getName());
            else cookies.put(c.getName(), c);
        }
        return actions;
    }

    /** 지금 들고 있는 쿠키 값. 없으면 null */
    public String cookie(String name) {
        Cookie c = cookies.get(name);
        return c == null ? null : c.getValue();
    }

    public boolean hasSessionCookie() {
        return cookies.containsKey("BLOGSESSION");
    }
}
