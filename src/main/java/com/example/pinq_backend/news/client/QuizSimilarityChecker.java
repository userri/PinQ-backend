package com.example.pinq_backend.news.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 과거 출제 이력과의 표면(렉시컬) 유사도로 중복 퀴즈를 걸러내는 검사기.
 *
 * 검증 흐름에서의 위치: GPT 생성 → 룰베이스 검증 → Claude 검증 → [여기] → 저장.
 * (실제 호출은 QuizGenerationService 가 저장 직전에 수행)
 *
 * 왜 렉시컬 검사인가:
 *  - 운영 데이터(172개) 분석 결과 중복의 대부분이 표현까지 유사한 변주였음
 *    (예: "미국 국채 금리가 상승하면 … 영향은 무엇인가요?" 가 3주간 14회 변주 출제)
 *  - API 호출 0원, 1ms 미만, 결정적(같은 입력 같은 결과)
 *  - 프롬프트에 "중복 금지"를 지시해도 모델이 무시할 수 있으므로 코드 레벨 최종 방어선이 필요
 *  - 표현이 완전히 다른 '의미적 중복'은 이 검사로 못 잡으며, 그쪽은 생성·검증
 *    프롬프트에 주입되는 최근 출제 이력이 맡는다 (역할 분담)
 *
 * 판정 기준 (아래 셋 중 하나라도 충족하면 중복):
 *  - [동시 충족] 토큰 Jaccard >= 0.5 AND bigram Dice >= 0.6
 *    : 두 지표가 서로의 오탐을 걸러준다. 단일 지표 임계보다 낮게 잡아 재현율 확보.
 *  - [Jaccard 압도] 토큰 Jaccard >= 0.75 : 단어 구성이 거의 같은 변주.
 *  - [Dice 압도] bigram Dice >= 0.85, 단 정규화 문자열이 양쪽 모두 20자 이상일 때만
 *    : 짧은 문장은 공유 접미("~금리란 무엇인가")만으로 Dice가 부풀어 오르므로 길이 가드 필요.
 *
 * 임계값 근거: 운영 퀴즈 172개 + 코드 리뷰에서 확인된 오탐 사례로 재캘리브레이션.
 *  - 실제 중복 쌍(동일 문항 쌍, "미국채 금리↑→주식" 변주, "변동금리 이자 부담" 변주)은 모두 검출
 *  - 오탐 사례는 모두 기준 미만: 짧은 정의형("콜금리란 무엇인가?" vs "기준금리란 무엇인가?" D=0.80),
 *    "~에 미치는 영향은?" 어미가 bigram을 지배하는 템플릿 쌍(D 0.6~0.71 구간),
 *    Jaccard 단독 고득점인 다른 개념 쌍("국채금리→채권가격" vs "채권금리→주담대" J=0.67)
 *  - 트레이드오프: 어휘 겹침이 낮은 의미적 변주(예: J=0.23/D=0.68 어순 재배열)는 여기서 잡지 않고
 *    생성·검증 프롬프트의 이력 주입(의미 층)에 위임한다. 렉시컬 층의 오탐은 정상 퀴즈를
 *    조용히 폐기시키므로(보상 장치 없음), 이 층은 정밀도(precision)를 우선한다.
 */
@Component
public class QuizSimilarityChecker {

    static final double JOINT_JACCARD_THRESHOLD = 0.5;
    static final double JOINT_DICE_THRESHOLD = 0.6;
    static final double SOLO_JACCARD_THRESHOLD = 0.75;
    static final double SOLO_DICE_THRESHOLD = 0.85;
    /** Dice 단독 판정을 허용하는 최소 정규화 문자열 길이 (짧은 문장 Dice 인플레 가드) */
    static final int SOLO_DICE_MIN_LENGTH = 20;

    /** 한글·영숫자 연속 청크 (공백·문장부호 기준 분리) */
    private static final Pattern CHUNK = Pattern.compile("[가-힣a-zA-Z0-9]+");

    /** bigram 계산 전 제거할 문자 (공백·문장부호) */
    private static final Pattern NON_CONTENT = Pattern.compile("[^가-힣a-zA-Z0-9]");

    /**
     * 토큰 끝에서 떼어낼 대표 조사. 긴 것부터 매칭한다.
     * 형태소 분석기 없이 접미 제거만 하는 근사이지만, 비교 대상 양쪽에
     * 동일하게 적용되므로 유사도 계산 목적으로는 충분하다.
     */
    private static final List<String> JOSA = List.of(
            "에서", "으로", "이나", "부터", "까지", "처럼", "보다",
            "은", "는", "이", "가", "을", "를", "의", "에", "로", "와", "과", "도", "만", "나"
    );

    /** 최소 토큰 길이. 조사 제거 후 1글자 조각은 노이즈라 버린다. */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 중복 판정 결과. 어떤 기존 문항과 얼마나 유사했는지 로그용으로 담는다. */
    public record Match(String existingQuestion, double tokenJaccard, double bigramDice) {}

    /**
     * 후보 문항이 이력 중 어떤 문항과라도 기준 이상 유사하면, 가장 유사한 문항을 반환한다.
     *
     * @param candidateQuestion 새로 생성된 퀴즈 문항
     * @param historyQuestions  최근 출제된 문항 목록
     * @return 기준 초과 문항이 있으면 가장 유사한 Match, 없으면 empty
     */
    public Optional<Match> findMostSimilar(String candidateQuestion, Collection<String> historyQuestions) {
        if (candidateQuestion == null || candidateQuestion.isBlank()
                || historyQuestions == null || historyQuestions.isEmpty()) {
            return Optional.empty();
        }

        Set<String> candidateTokens = tokenize(candidateQuestion);
        String candidateNormalized = normalize(candidateQuestion);
        Set<String> candidateBigrams = bigrams(candidateNormalized);

        Match best = null;
        for (String history : historyQuestions) {
            if (history == null || history.isBlank()) continue;

            String historyNormalized = normalize(history);
            double jaccard = jaccard(candidateTokens, tokenize(history));
            double dice = dice(candidateBigrams, bigrams(historyNormalized));
            int minLength = Math.min(candidateNormalized.length(), historyNormalized.length());
            if (!isDuplicate(jaccard, dice, minLength)) continue;

            if (best == null || jaccard + dice > best.tokenJaccard() + best.bigramDice()) {
                best = new Match(history, jaccard, dice);
            }
        }
        return Optional.ofNullable(best);
    }

    /** 클래스 javadoc의 3중 판정 기준. minNormalizedLength 는 두 문장 중 짧은 쪽의 정규화 길이. */
    private boolean isDuplicate(double jaccard, double dice, int minNormalizedLength) {
        if (jaccard >= JOINT_JACCARD_THRESHOLD && dice >= JOINT_DICE_THRESHOLD) return true;
        if (jaccard >= SOLO_JACCARD_THRESHOLD) return true;
        return dice >= SOLO_DICE_THRESHOLD && minNormalizedLength >= SOLO_DICE_MIN_LENGTH;
    }

    /** 한글·영숫자 청크 추출 → 조사 제거 → 2글자 미만 버림. */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = CHUNK.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = stripJosa(matcher.group());
            if (token.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String stripJosa(String chunk) {
        for (String josa : JOSA) {
            if (chunk.endsWith(josa) && chunk.length() - josa.length() >= MIN_TOKEN_LENGTH) {
                return chunk.substring(0, chunk.length() - josa.length());
            }
        }
        return chunk;
    }

    private String normalize(String text) {
        return NON_CONTENT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private Set<String> bigrams(String normalized) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return (double) intersection / union;
    }

    private double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long intersection = a.stream().filter(b::contains).count();
        return 2.0 * intersection / (a.size() + b.size());
    }
}
