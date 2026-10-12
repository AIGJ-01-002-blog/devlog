package com.team.blog.shared.security;

import java.io.Serial;
import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class MemberAuthentication extends AbstractAuthenticationToken {
    @Serial
    private static final long serialVersionUID = 1L;
    private final MemberPrincipal principal;

    public MemberAuthentication(MemberPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public MemberPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return String.valueOf(principal.id());
    }

    // 상위 비교가 getPrincipal()로 회원까지 비교한다
    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
