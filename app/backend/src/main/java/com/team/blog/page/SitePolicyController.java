package com.team.blog.page;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 이용약관·개인정보 처리방침 본문 (spec 085). 화면 안에서 옮겨 와 서버가 첫 화면 데이터를 넣지 못했을 때 React가 읽는다. */
@RestController
public class SitePolicyController {
    private final PolicyDocuments policies;

    public SitePolicyController(PolicyDocuments policies) {
        this.policies = policies;
    }

    @GetMapping("/api/site/policies/{kind}")
    public ResponseEntity<PolicyDocuments.Document> policy(@PathVariable String kind) {
        return PolicyDocuments.Kind.of(kind).map(k -> ResponseEntity.ok(policies.get(k)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
