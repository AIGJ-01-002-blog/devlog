package com.team.blog.search.semantic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.ai.application.AiProperties;

/**
 * 임베딩 공급자 호출 (spec 054). 여러 글을 한 번의 요청으로 묶어 보낸다(Ollama /api/embed의 input 배열,
 * Gemini batchEmbedContents). 어느 공급자를 쓸지는 시작할 때 한 번 정하고, 저장하는 벡터에는 모델 이름을 함께 적는다.
 * 열쇠값·접근 토큰은 머리글로만 보내 주소·로그에 남지 않는다.
 */
@Component
public class EmbeddingClient {
    /** 글(문서)과 검색어. Gemini는 둘을 다르게 임베딩해야 검색이 잘 맞는다 */
    public enum Purpose { DOCUMENT, QUERY }

    public enum Provider { LOCAL, GEMINI, NONE }

    /** 연결 실패·시간 초과·오류 응답. 부르는 쪽은 키워드 검색으로 돌아간다 */
    public static class EmbedException extends Exception {
        public EmbedException(String message) {
            super(message);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final SemanticProperties props;
    private final AiProperties ai;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Provider provider;
    private final String localBaseUrl;
    private final Map<String, String> localHeaders;

    public EmbeddingClient(SemanticProperties props, AiProperties ai) {
        this.props = props;
        this.ai = ai;
        boolean ownServer = !props.baseUrl().isBlank();
        this.localBaseUrl = ownServer ? props.baseUrl() : ai.local().baseUrl();
        // 서버 안 Ollama는 같은 네임스페이스 안에서만 열려 있어 접근 토큰이 없다. 집 PC는 터널 접근 토큰을 붙인다
        this.localHeaders = ownServer ? Map.of() : ai.local().authHeaders();
        this.provider = choose(props.provider(), ownServer || ai.local().configured(), ai.gemini().configured(), props.enabled());
    }

    static Provider choose(String wanted, boolean local, boolean gemini, boolean enabled) {
        if (!enabled) return Provider.NONE;
        return switch (wanted == null ? "auto" : wanted.toLowerCase()) {
            case "local" -> local ? Provider.LOCAL : Provider.NONE;
            case "gemini" -> gemini ? Provider.GEMINI : Provider.NONE;
            default -> {
                if (local) yield Provider.LOCAL;
                yield gemini ? Provider.GEMINI : Provider.NONE;
            }
        };
    }

    public Provider provider() {
        return provider;
    }

    public boolean configured() {
        return provider != Provider.NONE;
    }

    /** 저장하는 벡터에 적는 모델 이름. 공급자가 바뀌면 이름도 바뀌어 예전 벡터와 비교하지 않는다 */
    public String model() {
        return switch (provider) {
            case LOCAL -> "ollama:" + props.localModel();
            case GEMINI -> "gemini:" + props.geminiModel() + ":" + props.geminiDimensions();
            case NONE -> "none";
        };
    }

    public double maxDistance() {
        return provider == Provider.GEMINI ? props.geminiMaxDistance() : props.localMaxDistance();
    }

    /** @return 입력과 같은 순서의 벡터 */
    public List<float[]> embed(List<String> texts, Purpose purpose, Duration timeout) throws EmbedException {
        if (texts.isEmpty()) return List.of();
        return switch (provider) {
            case LOCAL -> ollama(texts, timeout);
            case GEMINI -> gemini(texts, purpose, timeout);
            case NONE -> throw new EmbedException("임베딩 공급자가 설정되지 않았습니다");
        };
    }

    private List<float[]> ollama(List<String> texts, Duration timeout) throws EmbedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", props.localModel());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        body.put("truncate", true);
        JsonNode root = post(trimSlash(localBaseUrl) + "/api/embed", localHeaders, body, timeout);
        return vectors(root.path("embeddings"), null, texts.size());
    }

    private List<float[]> gemini(List<String> texts, Purpose purpose, Duration timeout) throws EmbedException {
        String model = "models/" + props.geminiModel();
        ObjectNode body = json.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        for (String t : texts) {
            ObjectNode r = requests.addObject();
            r.put("model", model);
            r.putObject("content").putArray("parts").addObject().put("text", t);
            r.put("taskType", purpose == Purpose.QUERY ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT");
            r.put("outputDimensionality", props.geminiDimensions());
        }
        String url = trimSlash(ai.gemini().baseUrl()) + "/v1beta/" + model + ":batchEmbedContents";
        JsonNode root = post(url, Map.of("x-goog-api-key", ai.gemini().apiKey()), body, timeout);
        return vectors(root.path("embeddings"), "values", texts.size());
    }

    private List<float[]> vectors(JsonNode list, String field, int expected) throws EmbedException {
        if (!list.isArray() || list.size() != expected) throw new EmbedException("임베딩 응답 개수가 맞지 않습니다");
        List<float[]> out = new ArrayList<>(expected);
        for (JsonNode item : list) {
            JsonNode values = field == null ? item : item.path(field);
            if (!values.isArray() || values.isEmpty()) throw new EmbedException("임베딩 응답 형식이 올바르지 않습니다");
            float[] v = new float[values.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) values.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    private JsonNode post(String url, Map<String, String> headers, ObjectNode body, Duration timeout) throws EmbedException {
        Map<String, String> all = new LinkedHashMap<>(headers);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        all.forEach(req::header);
        HttpResponse<String> res;
        try {
            res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new EmbedException("임베딩 공급자에 연결하지 못했습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbedException("임베딩 요청이 중단되었습니다");
        }
        // 응답 본문은 남기지 않는다 (공급자가 요청 내용을 되돌려 줄 수 있다)
        if (res.statusCode() != 200) throw new EmbedException("임베딩 공급자 응답 " + res.statusCode());
        try {
            return json.readTree(res.body());
        } catch (RuntimeException e) {
            throw new EmbedException("임베딩 응답을 읽지 못했습니다");
        }
    }

    private static String trimSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** pgvector 글자 표기: [0.1,0.2,...] */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
