package com.team.blog.search.semantic;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 하이브리드 검색의 의미 검색 쪽 설정 (spec 054). 임베딩 공급자 주소·열쇠값은 AI 설정(blog.ai)을 그대로 쓴다.
 *
 * @param enabled        끄면 검색은 키워드만 쓴다
 * @param provider       auto(서버 안 임베딩 주소 → 집 PC → Gemini 순으로 설정된 것), local, gemini. 모델이 섞이지 않게 실행 중에는 바꾸지 않는다
 * @param baseUrl        서버 안 Ollama 임베딩 주소 (예: http://ollama-embed:11434). 비우면 집 PC 주소(blog.ai.local)를 쓴다.
 *                       검색어도 검색할 때마다 임베딩해야 해서, 집 PC가 꺼져도 하이브리드가 돌도록 서버 안에 둔다
 * @param localModel     Ollama 임베딩 모델 (서버 안·집 PC 같은 모델이라 벡터가 섞여도 된다)
 * @param geminiModel    Gemini 임베딩 모델
 * @param geminiDimensions Gemini 임베딩 차원 (768·1536·3072 권장)
 * @param localMaxDistance  이보다 먼(코사인 거리) 글은 의미 검색 결과에서 뺀다. 모델마다 거리 분포가 달라 따로 둔다
 * @param geminiMaxDistance 위와 같음 (Gemini는 관계없는 글도 유사도가 높게 나와 더 좁게)
 * @param candidates     의미 검색에서 가져올 최대 글 수
 * @param keywordCandidates 섞을 키워드 결과 수. 이보다 많은 키워드 결과는 섞은 목록 뒤에 키워드 순서대로 이어진다
 * @param rrfK           순위 섞기(RRF) 상수. 클수록 아래 순위도 점수를 받는다
 * @param semanticWeight 의미 검색 순위의 무게 (키워드는 1). 1보다 작으면 제목·태그에 그대로 있는 글이 더 위에 남는다
 * @param maxChars       임베딩에 넣을 글자 수 (제목 + 태그 + 본문 앞부분)
 * @param queryTimeout   검색어 임베딩 시간 제한. 넘으면 이번 검색은 키워드만 쓴다
 * @param indexTimeout   글 임베딩 시간 제한
 * @param indexBatch     한 번 실행에서 임베딩할 글 수
 * @param indexChunk     한 요청에 묶을 글 수. CPU Ollama는 글 하나에 10초쯤 걸려 1개씩 보낸다(묶으면 indexTimeout을 넘긴다).
 *                       요청이 작으면 Ollama의 다른 자리가 비어 검색어 임베딩도 기다리지 않는다
 * @param indexInterval  글 임베딩 작업 간격
 * @param downFor        공급자 연결 실패 뒤 건너뛰는 시간 (집 PC가 꺼졌을 때 검색마다 기다리지 않게)
 * @param queryCacheTtl  같은 검색어 임베딩 보관 기간
 * @param warmTimeout    예열(검색창을 열 때 모델을 미리 올리기) 요청 시간 제한. 모델을 디스크에서 올리는 시간까지 기다린다
 * @param warmEvery      예열은 여러 사람이 검색창을 열어도 이 간격에 한 번만 보낸다. 이 동안 검색어 임베딩이 늦어도 내려간 것으로 보지 않는다
 */
@ConfigurationProperties("blog.search.semantic")
public record SemanticProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("auto") String provider,
                                 @DefaultValue("") String baseUrl,
                                 @DefaultValue("bge-m3") String localModel,
                                 @DefaultValue("gemini-embedding-001") String geminiModel,
                                 @DefaultValue("768") int geminiDimensions,
                                 @DefaultValue("0.55") double localMaxDistance,
                                 @DefaultValue("0.4") double geminiMaxDistance,
                                 @DefaultValue("30") int candidates,
                                 @DefaultValue("100") int keywordCandidates,
                                 @DefaultValue("60") int rrfK,
                                 @DefaultValue("0.8") double semanticWeight,
                                 @DefaultValue("3000") int maxChars,
                                 @DefaultValue("3s") Duration queryTimeout,
                                 @DefaultValue("30s") Duration indexTimeout,
                                 @DefaultValue("20") int indexBatch,
                                 @DefaultValue("1") int indexChunk,
                                 @DefaultValue("60s") Duration indexInterval,
                                 @DefaultValue("60s") Duration downFor,
                                 @DefaultValue("1d") Duration queryCacheTtl,
                                 @DefaultValue("30s") Duration warmTimeout,
                                 @DefaultValue("60s") Duration warmEvery) {}
