package com.team.blog.ai.infra;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.ai.application.AiProperties;
import com.team.blog.ai.application.TagModel;

/** 자체 AI: 우리 서버의 Ollama /api/chat (018 FR-014). 외부로 나가지 않는다. 응답 형식은 JSON Schema로 건다. */
@Component
class OllamaModel implements TagModel {
    private static final String CONTENT = "content";
    private final AiProperties.Local props;
    private final JsonMapper json = JsonMapper.builder().build();

    OllamaModel(AiProperties props) {
        this.props = props.local();
    }

    @Override
    public Provider provider() {
        return Provider.LOCAL;
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
        body.put("model", props.model());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put(CONTENT, prompt.system());
        messages.addObject().put("role", "user").put(CONTENT, prompt.user());
        body.set("format", json.readTree(schema));
        body.putObject("options").put("temperature", temperature).put("num_predict", maxTokens);
        HttpJson.Response res = HttpJson.post(HttpJson.trimSlash(props.baseUrl()) + "/api/chat", props.authHeaders(),
                json.writeValueAsString(body), props.timeout());
        if (res.status() != 200) throw new ModelException(ModelException.Kind.FAILURE);
        JsonNode root;
        try {
            root = json.readTree(res.body());
        } catch (RuntimeException e) {
            throw new ModelException(ModelException.Kind.INVALID);
        }
        JsonNode content = root.path("message").path(CONTENT);
        return content.isString() ? content.asString() : null;
    }
}
