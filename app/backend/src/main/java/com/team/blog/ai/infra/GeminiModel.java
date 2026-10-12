package com.team.blog.ai.infra;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.ai.application.AiProperties;
import com.team.blog.ai.application.TagModel;

/**
 * 외부 AI: Gemini generateContent (018 FR-014·FR-016). 열쇠값은 머리글로만 보내 주소·로그에 남지 않는다(FR-033).
 * 429는 본문의 할당량 이름(…PerDay… / …PerMinute…)으로 하루·분당을 가른다. 그 밖의 4xx·5xx·시간 초과는 FAILURE다.
 */
@Component
class GeminiModel implements TagModel {
    private static final String PARTS = "parts";
    private final AiProperties.Gemini props;
    private final JsonMapper json = JsonMapper.builder().build();

    GeminiModel(AiProperties props) {
        this.props = props.gemini();
    }

    @Override
    public Provider provider() {
        return Provider.GEMINI;
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    public int maxChars() {
        return props.maxChars();
    }

    @Override
    public String complete(Prompt prompt, String schema, int maxTokens, double temperature) throws ModelException {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray(PARTS).addObject().put("text", prompt.system());
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray(PARTS).addObject().put("text", prompt.user());
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", temperature);
        config.put("maxOutputTokens", maxTokens);
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", json.readTree(schema));
        String url = HttpJson.trimSlash(props.baseUrl()) + "/v1beta/models/" + props.model() + ":generateContent";
        HttpJson.Response res = HttpJson.post(url, Map.of("x-goog-api-key", props.apiKey()), json.writeValueAsString(body), props.timeout());
        if (res.status() == 429) throw new ModelException(quotaKind(res.body()));
        if (res.status() != 200) throw new ModelException(ModelException.Kind.FAILURE);
        JsonNode root;
        try {
            root = json.readTree(res.body());
        } catch (RuntimeException e) {
            throw new ModelException(ModelException.Kind.INVALID);
        }
        return text(root);
    }

    static ModelException.Kind quotaKind(String body) {
        if (body == null) return ModelException.Kind.UNKNOWN_QUOTA;
        if (body.contains("PerDay")) return ModelException.Kind.DAILY_QUOTA;
        if (body.contains("PerMinute")) return ModelException.Kind.MINUTE_QUOTA;
        return ModelException.Kind.UNKNOWN_QUOTA;
    }

    private static String text(JsonNode root) {
        JsonNode parts = root.path("candidates").path(0).path("content").path(PARTS);
        StringBuilder s = new StringBuilder();
        for (JsonNode p : parts) s.append(p.path("text").asString(""));
        return s.isEmpty() ? null : s.toString();
    }
}
