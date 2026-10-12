package com.team.blog.ai.application;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 지시문과 응답 형식 (018 FR-008·FR-010·FR-011). 바꾸면 {@link AiInput#PROMPT_VERSION}을 올린다. */
public final class TagPrompt {
    public static final int MAX_TAGS = 5;

    static final String SYSTEM = """
            당신은 개발 블로그 글에 붙일 태그를 추천합니다.
            규칙:
            - 글의 핵심 기술·주제를 나타내는 태그를 최대 5개 고르세요.
            - 글에 나오지 않는 기술은 넣지 마세요.
            - 영어 소문자와 하이픈만, 형식대로만 답하세요. 예: spring-boot, react, postgresql
            - 인기 태그 중 글에 맞는 것이 있으면 그 이름을 그대로 쓰세요.
            - 이미 붙인 태그는 제외하세요.
            - 답은 {"tags": ["태그", ...]} 형식의 JSON 하나뿐입니다. 설명을 붙이지 마세요.""";

    /** 두 공급자에 똑같이 거는 응답 형식 (JSON Schema 부분 집합: Gemini responseSchema·Ollama format 모두 읽는다) */
    public static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":5}},\"required\":[\"tags\"]}";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TagPrompt() {}

    /**
     * @param popular  인기 태그 (자체 AI에는 글에 실제로 나오는 것만, FR-009)
     * @param attached 지금 붙인 태그 ("이미 붙인 태그는 제외")
     */
    public static TagModel.Prompt build(String title, String body, Collection<String> popular, Collection<String> attached) {
        StringBuilder u = new StringBuilder();
        if (!popular.isEmpty()) u.append("인기 태그: ").append(String.join(", ", popular)).append('\n');
        if (!attached.isEmpty()) u.append("이미 붙인 태그: ").append(String.join(", ", attached)).append('\n');
        u.append("제목: ").append(title).append('\n').append("본문: ").append(body);
        return new TagModel.Prompt(SYSTEM, u.toString());
    }

    /** 자체 AI용: 인기 태그 중 글(제목·본문)에 실제로 나오는 것만 */
    public static List<String> mentioned(Collection<String> popular, String title, String body) {
        String text = (title + " " + body).toLowerCase(Locale.ROOT);
        return popular.stream().filter(t -> text.contains(t) || text.contains(t.replace('-', ' '))).toList();
    }

    /**
     * 응답 검사 (FR-011): {"tags": [문자열 최대 5개]}만 받는다. 작은 모델이 코드 블록으로 감싸 보내는 경우만 벗긴다.
     * @throws TagModel.ModelException INVALID
     */
    public static List<String> parse(String text) throws TagModel.ModelException {
        if (text == null) throw invalid();
        String s = text.strip();
        if (s.startsWith("```")) {
            // ```json ... ``` 울타리를 벗긴다. 끝은 정규식 없이 잘라 긴 응답에서도 한 번만 훑는다
            s = s.substring(3).replaceFirst("^[a-zA-Z]*+", "");
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        JsonNode root;
        try {
            root = JSON.readTree(s);
        } catch (RuntimeException e) {
            throw invalid();
        }
        JsonNode tags = root == null ? null : root.get("tags");
        if (tags == null || !tags.isArray() || tags.size() > MAX_TAGS) throw invalid();
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode t : tags) {
            if (!t.isString()) throw invalid();
            out.add(t.asString());
        }
        return out;
    }

    private static TagModel.ModelException invalid() {
        return new TagModel.ModelException(TagModel.ModelException.Kind.INVALID);
    }
}
