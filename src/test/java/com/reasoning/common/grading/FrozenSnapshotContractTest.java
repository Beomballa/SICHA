package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.GradeModels;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** 순수 전체 codec→집합→선택→모델 경계를 검사한다. 영속 시작·인가·실제 모델 품질을 증명하지 않는다. */
class FrozenSnapshotContractTest {
    private static final String CHECKER = "11111111-1111-4111-8111-111111111111";
    private static final List<String> RESOURCES =
            List.of(
                    "persons",
                    "roles",
                    "pairs",
                    "clues",
                    "clueRoles",
                    "hints",
                    "events",
                    "facts",
                    "rubrics",
                    "rubricClues",
                    "gradeSamples");
    private static final String CULPRIT_RULE =
            """
            {"formatNo":1,"requiredNotice":"선택한 범인을 근거로 입증하세요.",
             "claims":[{"code":"SELECTED_CULPRIT","meaning":"선택한 범인이 사건의 실제 범인과 일치한다.","factCodes":[],"exampleClueRoutes":[]}],
             "levels":[{"code":"ZERO","score":0,"routes":[]},{"code":"FULL","score":25,"routes":[["SELECTED_CULPRIT"]]}],
             "contradictions":[{"code":"CONTRADICT_CULPRIT","meaning":"선택한 범인과 본문에서 최종 단정한 범인이 서로 충돌한다."},
              {"code":"UNSUPPORTED_ACCOMPLICE","meaning":"고정 사실과 타당한 증거 연결로 뒷받침되지 않거나 단독범행 사실과 양립하지 않는 공범을 최종 단정한다."}]}
            """;

    @Test
    void fullRoundTripRetainsServerRulesAndProjectsExactlyOneReport() {
        ObjectNode original = complete();
        FrozenSnapshot encoded = FrozenSnapshotCodec.freeze(original);
        FrozenSnapshot decoded = FrozenSnapshotCodec.decode(encoded.payloadBytes());
        assertThat(decoded.payloadBytes()).isEqualTo(encoded.payloadBytes());
        assertThat(decoded.payloadHash()).isEqualTo(encoded.payloadHash());
        var dataset = new FrozenDatasetValidator().validate(decoded);
        var selected = dataset.select("FULL");
        var projection = FrozenModelProjection.project(dataset, selected);
        assertThat(selected.report().method()).isEqualTo("SELECTED_REPORT");
        assertThat(selected.expectedScore()).isEqualTo(100);
        assertThat(selected.expectedSuccess()).isTrue();
        assertThat(dataset.gradingSnapshot().rubrics().getFirst().claims().getFirst().code())
                .isEqualTo("SELECTED_CULPRIT");
        JsonNode model = projection.payload();
        assertThat(model.size()).isEqualTo(3);
        assertThat(model.get("gradingContext").size()).isEqualTo(5);
        JsonNode culprit = model.get("gradingContext").get("rubrics").get(0).get("ruleData");
        assertThat(culprit.get("claims")).isEmpty();
        for (JsonNode level : culprit.get("levels")) assertThat(level.get("routes")).isEmpty();
        assertThat(culprit.get("contradictions")).hasSize(2);
        assertThat(model.get("gradingContext").get("persons")).hasSize(2);
        assertThat(model.get("gradingContext").get("facts"))
                .isEqualTo(decoded.payload().get("resources").get("facts"));
        assertThat(selected.fault()).isNull();
        assertThat(dataset.select("ENGINE").fault().failRuns()).isEqualTo(3);
        assertThat(dataset.select("INPUT").report()).isNull();
        assertThatThrownBy(() -> FrozenModelProjection.project(dataset, dataset.select("INPUT")))
                .hasMessage("INVALID_MODEL_PROJECTION")
                .hasNoCause();
        var other = new FrozenDatasetValidator().validate(decoded);
        assertThatThrownBy(() -> FrozenModelProjection.project(dataset, other.select("FULL")))
                .hasMessage("INVALID_MODEL_PROJECTION")
                .hasNoCause();
        assertThatThrownBy(() -> dataset.select("MISSING"))
                .hasMessage("INVALID_FROZEN_DATASET")
                .hasNoCause();
    }

    /** JSONB가 저장할 수 없거나 공유 parser가 읽을 수 없는 수는 저장 전에 거절한다. */
    @Test
    void frozenNumbersMustBeJsonbRepresentableAndReadableWithoutExpandingHugeStrings() {
        for (String number : List.of("1e-20000", "1e1000", "1e-1001")) {
            ObjectNode root = complete();
            ObjectNode malformed =
                    ((ObjectNode) sample(root, "INPUT").get("inputData")).putObject("report");
            malformed.set("number", JsonNodeFactory.instance.numberNode(new BigDecimal(number)));
            assertThatThrownBy(() -> FrozenSnapshotCodec.freeze(root))
                    .as("JSONB 저장/읽기 불가 수 %s", number)
                    .hasMessage("INVALID_FROZEN_SNAPSHOT")
                    .hasNoCause();
        }
    }

    /** Python의 별도 키/튜플 정렬·UTF-8 JSON·hashlib 계산으로 작성한 고정 벡터를 대조한다. */
    @Test
    void independentLiteralHashesPinAllFiveSnapshotIdentities() {
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(complete());
        var report = new FrozenDatasetValidator().validate(frozen).select("FULL").report();
        assertThat(frozen.payloadBytes()).hasSize(7891);
        assertThat(frozen.payloadHash())
                .isEqualTo("63f2514c069e4ce385457fd2e0e97a7a97852a15392bd213682fa2cc4341a681");
        assertThat(frozen.rubricHash())
                .isEqualTo("1895f8e3d1ded8cec2cc9b5f700c86f799fcf0c10de837aef55b572e0ac03c4a");
        assertThat(frozen.datasetHash())
                .isEqualTo("824e3c8b847f100396f6687676b174f04193333cd7054fae5647eaad23d638fd");
        assertThat(frozen.inputHash("FULL"))
                .isEqualTo("41135c650637570bcaa2973b2ba48fd4014c4fe39ca4a056f7966cc980cf249f");
        assertThat(frozen.reportHash(report))
                .isEqualTo("8f39d14c78008a56d6c4b22a2ccfd0f5a4c76547517bdd1897c29a59759ff52c");
    }

    @Test
    void literalCanonicalWirePinsEveryPolicyFieldAndNullStorageDistinction() {
        // 독립 literal 바이트는 구현 serializer 호출로 기대값을 생성하지 않는다.
        String expected =
                """
                {"formatNo":1,"policy":{"attemptLimit":3,"categoryScores":{"CULPRIT":25,"EVIDENCE":30,"METHOD":20,"MOTIVE":10,"TIME":15},"hintPenalty":0,"hints":{"levels":[1,2,3],"repeatOpenConsumes":false,"requirePreviousLevel":false},"hintsPerPerson":2,"limitSec":900,"policyCode":"RULE_20260924","recovery":{"exhaustedOutcome":"SYSTEM_ERROR","exhaustionRule":"FIRST_OF_THIRD_FAILED_CALL_OR_DEADLINE","includesQueueAndRetryDelay":true,"maxCalls":3,"resetBudgetOnRestart":false,"resultBoundary":"BEFORE_RECOVERY_DEADLINE","retryInput":"SAME_FROZEN_REPORT_AND_CRITERIA","timeoutSec":120},"scoring":{"baseScoreMode":"SUM_ITEM_SCORES","contradictionScore":0,"finalScoreMode":"LAST_NORMAL_BASE_MINUS_WRONG_COUNT_TIMES_WRONG_PENALTY","incompleteFinalScore":null,"levelSelection":"HIGHEST_SATISFIED_ROUTE_OR_ZERO","minimumFinalScore":0,"penaltiesAffectSuccess":false,"requiredMode":"ALL_REQUIRED_AT_PASS_SCORE","timeLimitWithoutNormalReportScore":0},"submissions":{"agreementMode":"BOTH_SAME_REPORT","attemptConsumption":"NORMAL_JUDGMENT_ONLY","errorAttemptDelta":0,"normalAttemptDelta":1,"normalWrongDelta":1,"pendingBlocks":["REPORT_EDIT","NEW_SUBMISSION","NEW_HINT","FORFEIT"],"retryNormalWrong":false},"time":{"acceptanceBoundary":"BEFORE_PLAY_DEADLINE","clockContinuesOffline":true,"normalOutcomeOrder":["SUCCESS","TIME_LIMIT","ATTEMPTS_EXHAUSTED","CONTINUE"],"pendingAtPlayDeadline":"WAIT_FOR_FIXED_RECOVERY"},"timePenalty":0,"wrongPenalty":10},"resources":{"clueRoles":[],"clues":[],"events":[],"facts":[],"gradeSamples":[],"hints":[],"pairs":[],"persons":[],"roles":[],"rubricClues":[],"rubrics":[]},"sections":{"answer":{"culpritCode":null,"methodAnswer":null,"motiveAnswer":null,"timeAnswer":null},"basic":{"difficulty":3,"estMax":null,"estMin":null,"intro":null,"limitSec":900,"setting":null,"timelineOrigin":null,"title":"제목"},"reveal":{"revealText":null}},"sourceRev":"0","storyCode":"ST_TEST","versionNo":1}
                """
                        .strip();
        FrozenSnapshot minimal = FrozenSnapshotCodec.freeze(empty());
        assertThat(minimal.payloadBytes()).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(
                        FrozenSnapshotCodec.decode(expected.getBytes(StandardCharsets.UTF_8))
                                .payloadBytes())
                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new FrozenDatasetValidator().validate(minimal))
                .hasMessage("INVALID_FROZEN_DATASET");
    }

    @Test
    void derivedIdentitiesUseTheExactApprovedWrappersAndOriginalSelectedInput() {
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(complete());
        JsonNode payload = frozen.payload();
        JsonNode resources = payload.get("resources");
        ObjectNode rubricPreimage =
                object().put("formatNo", 1).put("payloadHash", frozen.payloadHash());
        rubricPreimage.set("rubrics", resources.get("rubrics"));
        rubricPreimage.set("rubricClues", resources.get("rubricClues"));
        assertThat(frozen.rubricHash()).isEqualTo(SnapshotJson.hash(rubricPreimage));
        ObjectNode datasetPreimage =
                object().put("formatNo", 1)
                        .put("payloadHash", frozen.payloadHash())
                        .put("rubricHash", frozen.rubricHash());
        datasetPreimage.set("gradeSamples", resources.get("gradeSamples"));
        assertThat(frozen.datasetHash()).isEqualTo(SnapshotJson.hash(datasetPreimage));
        for (String code : List.of("FULL", "INPUT", "ENGINE")) {
            ObjectNode inputPreimage =
                    object().put("formatNo", 1)
                            .put("payloadHash", frozen.payloadHash())
                            .put("rubricHash", frozen.rubricHash())
                            .put("datasetHash", frozen.datasetHash())
                            .put("sampleCode", code);
            inputPreimage.set("inputData", sample((ObjectNode) payload, code).get("inputData"));
            assertThat(frozen.inputHash(code)).isEqualTo(SnapshotJson.hash(inputPreimage));
        }
        String canonicalReport =
                "{\"culpritCode\":\"P1\",\"evidence\":\"\",\"method\":\"SELECTED_REPORT\",\"motive\":\"\",\"time\":\"\"}";
        JsonNode report = parse(canonicalReport);
        assertThat(SnapshotJson.encode(report))
                .isEqualTo(canonicalReport.getBytes(StandardCharsets.UTF_8));
        ObjectNode reportPreimage =
                object().put("formatNo", 1)
                        .put("payloadHash", frozen.payloadHash())
                        .put("rubricHash", frozen.rubricHash());
        reportPreimage.set("report", report);
        GradeModels.Report selected =
                new FrozenDatasetValidator().validate(frozen).select("FULL").report();
        assertThat(frozen.reportHash(selected)).isEqualTo(SnapshotJson.hash(reportPreimage));
        assertThat(frozen.inputHash("FULL")).isNotEqualTo(frozen.inputHash("INPUT"));
        assertThat(frozen.reportHash(selected)).isNotEqualTo(frozen.inputHash("FULL"));
    }

    @Test
    void exactIntegralNumbersAndObjectOrderHaveEquivalentIdentitiesButNeverCoerce() {
        FrozenSnapshot original = FrozenSnapshotCodec.freeze(complete());
        String wire = new String(original.payloadBytes(), StandardCharsets.UTF_8);
        String equivalent =
                wire.replace("\"formatNo\":1", "\"formatNo\":1e0")
                        .replace("\"versionNo\":1", "\"versionNo\":1.0")
                        .replace("\"limitSec\":900", "\"limitSec\":900.00");
        FrozenSnapshot decoded =
                FrozenSnapshotCodec.decode(equivalent.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.payloadBytes()).isEqualTo(original.payloadBytes());
        assertThat(decoded.datasetHash()).isEqualTo(original.datasetHash());
        ObjectNode reordered = object();
        List<String> fields =
                List.of(
                        "resources",
                        "sections",
                        "policy",
                        "sourceRev",
                        "versionNo",
                        "storyCode",
                        "formatNo");
        fields.forEach(field -> reordered.set(field, original.payload().get(field)));
        assertThat(FrozenSnapshotCodec.freeze(reordered).payloadHash())
                .isEqualTo(original.payloadHash());
        for (String value : List.of("1.01", "2147483648", "\"1\"", "null")) {
            rejectsWire(wire.replace("\"versionNo\":1", "\"versionNo\":" + value));
        }
        for (String revision : List.of("00", "01", "-1", "9223372036854775808", "", "1.0")) {
            rejectsCodec(root -> root.put("sourceRev", revision));
        }
        rejectsCodec(root -> root.put("sourceRev", 0));
        ObjectNode maximumRevision = complete();
        maximumRevision.put("sourceRev", "9223372036854775807");
        assertThat(
                        FrozenSnapshotCodec.freeze(maximumRevision)
                                .payload()
                                .get("sourceRev")
                                .textValue())
                .isEqualTo("9223372036854775807");
        rejectsCodec(root -> root.put("versionNo", 0));
        rejectsCodec(root -> root.put("formatNo", 2));
        rejectsCodec(root -> root.put("callerHash", "f".repeat(64)));
        rejectsWire(wire + "{}");
        rejectsWire(wire.replace("\"formatNo\":1,", "\"formatNo\":1,\"formatNo\":1,"));
        assertThatThrownBy(() -> FrozenSnapshotCodec.decode(new byte[] {(byte) 0xc3, 0x28}))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
        assertThatThrownBy(() -> FrozenSnapshotCodec.decode(new byte[16 * 1024 * 1024 + 1]))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
    }

    @Test
    void allElevenProducerArraysSortButStrictConsumerNeverRepairs() {
        ObjectNode source = complete();
        for (String resource : RESOURCES) reverse(array(source, resource));
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(source);
        for (String resource : RESOURCES) {
            ArrayNode rows = array((ObjectNode) frozen.payload(), resource);
            assertThat(rows.size()).as(resource).isGreaterThan(1);
            ObjectNode badOrder = (ObjectNode) frozen.payload();
            reverse(array(badOrder, resource));
            assertThatThrownBy(() -> FrozenSnapshotCodec.decode(SnapshotJson.encode(badOrder)))
                    .as(resource)
                    .hasMessage("INVALID_FROZEN_SNAPSHOT");
            ObjectNode duplicate = (ObjectNode) frozen.payload();
            array(duplicate, resource).add(rows.get(0).deepCopy());
            assertThatThrownBy(() -> FrozenSnapshotCodec.freeze(duplicate))
                    .as(resource)
                    .hasMessage("INVALID_FROZEN_SNAPSHOT");
        }
        assertThat(array((ObjectNode) frozen.payload(), "hints").get(0).get("level").intValue())
                .isEqualTo(2);
        rejectsCodec(
                root ->
                        ((ObjectNode) array(root, "pairs").get(0))
                                .put("roleA", "R3")
                                .put("roleB", "R1"));
        rejectsCodec(root -> ((ObjectNode) array(root, "pairs").get(0)).put("roleB", "R1"));
        // 관계 종류가 다르면 같은 문자열도 유효하며 의미 위치를 서로 교환하지 않는다.
        ObjectNode sameCode = complete();
        ((ObjectNode) array(sameCode, "roles").get(0)).put("code", "C1");
        ((ObjectNode) array(sameCode, "pairs").get(0)).put("roleA", "C1");
        ((ObjectNode) array(sameCode, "pairs").get(1)).put("roleA", "C1");
        ((ObjectNode) array(sameCode, "clueRoles").get(0)).put("roleCode", "C1");
        var valid = new FrozenDatasetValidator().validate(FrozenSnapshotCodec.freeze(sameCode));
        assertThat(valid.select("FULL").report()).isNotNull();
    }

    @Test
    void policyIsCompleteClosedAndDifficultyAppliedWithoutFallback() {
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            JsonNode policy = FrozenSnapshotCodec.createPolicy("RULE_20260924", difficulty, 77);
            assertThat(policy.get("attemptLimit").intValue())
                    .isEqualTo(difficulty <= 2 ? 5 : difficulty <= 4 ? 3 : 2);
            assertThat(policy.get("hintsPerPerson").intValue())
                    .isEqualTo(difficulty <= 2 ? 3 : difficulty <= 4 ? 2 : 1);
            assertThat(policy.get("limitSec").intValue()).isEqualTo(77);
        }
        rejectsCodec(root -> ((ObjectNode) root.get("policy")).remove("recovery"));
        rejectsCodec(root -> ((ObjectNode) root.get("policy").get("recovery")).remove("maxCalls"));
        rejectsCodec(
                root ->
                        ((ObjectNode) root.get("policy").get("scoring"))
                                .remove("incompleteFinalScore"));
        rejectsCodec(root -> ((ObjectNode) root.get("policy")).put("policyCode", "UNKNOWN"));
        rejectsCodec(root -> ((ObjectNode) root.get("policy")).put("attemptLimit", 5));
        rejectsCodec(root -> ((ObjectNode) root.get("policy")).put("limitSec", 901));
        rejectsCodec(root -> ((ObjectNode) root.get("policy").get("recovery")).put("maxCalls", 4));
        rejectsCodec(
                root ->
                        ((ObjectNode) root.get("policy").get("submissions"))
                                .put("normalWrongDelta", 0));
        rejectsCodec(
                root ->
                        ((ObjectNode) root.get("policy").get("scoring"))
                                .put("incompleteFinalScore", 0));
        rejectsCodec(root -> ((ObjectNode) root.get("policy").get("hints")).put("automatic", true));
        rejectsCodec(
                root -> ((ObjectNode) root.get("sections").get("basic")).putNull("difficulty"));
        rejectsCodec(root -> ((ObjectNode) root.get("sections").get("basic")).put("limitSec", 0));
        assertThatThrownBy(() -> FrozenSnapshotCodec.createPolicy("UNKNOWN", 3, 900))
                .hasMessage("INVALID_FROZEN_SNAPSHOT");
    }

    @Test
    void wholeSetReferencesRulesCoverageAndHistoricalChecksCannotBeBypassedBySelection() {
        rejectsDataset(
                root ->
                        ((ObjectNode) root.get("sections").get("answer"))
                                .put("culpritCode", "OTHER"));
        rejectsDataset(
                root -> ((ObjectNode) array(root, "clues").get(1)).put("personCode", "OTHER"));
        rejectsDataset(root -> ((ObjectNode) array(root, "pairs").get(0)).put("roleB", "R9"));
        rejectsDataset(
                root -> ((ObjectNode) array(root, "clueRoles").get(0)).put("roleCode", "OTHER"));
        rejectsDataset(root -> ((ObjectNode) array(root, "clues").get(0)).put("scope", "COMMON"));
        rejectsDataset(root -> array(root, "clueRoles").remove(0));
        rejectsDataset(
                root -> ((ObjectNode) array(root, "rubricClues").get(0)).put("clueCode", "OTHER"));
        rejectsDataset(
                root ->
                        ((ObjectNode) rubric(root, "METHOD").get("ruleData").get("claims").get(0))
                                .putArray("factCodes")
                                .add("OTHER"));
        rejectsDataset(
                root ->
                        ((ObjectNode) rubric(root, "METHOD").get("ruleData").get("claims").get(0))
                                .putArray("exampleClueRoutes")
                                .addArray()
                                .add("C2"));
        rejectsDataset(
                root ->
                        ((ObjectNode) rubric(root, "METHOD").get("ruleData").get("levels").get(1))
                                .putArray("routes")
                                .addArray()
                                .add("OTHER"));
        rejectsDataset(root -> rubric(root, "METHOD").putNull("ruleData"));
        // 개별 passScore 범위는 유지해 전체 분류 배점 불일치를 실제로 검증한다.
        rejectsDataset(
                root -> {
                    rubric(root, "METHOD").put("maxScore", 19).put("passScore", 19);
                    ((ObjectNode) rubric(root, "METHOD").get("ruleData").get("levels").get(1))
                            .put("score", 19);
                    expectedItem(root, "FULL", "METHOD").put("score", 19);
                    sample(root, "FULL").put("expectedScore", 99);
                });
        rejectsDataset(
                root -> {
                    rubric(root, "METHOD").put("requiredYn", false).putNull("passScore");
                    ((ObjectNode) rubric(root, "METHOD").get("ruleData")).putNull("requiredNotice");
                    expectedItem(root, "FULL", "METHOD").putNull("requiredMet");
                    expectedItem(root, "ZERO", "METHOD").putNull("requiredMet");
                });
        rejectsDataset(
                root ->
                        ((ObjectNode) rubric(root, "CULPRIT").get("ruleData").get("claims").get(0))
                                .put("meaning", "변조"));
        rejectsDataset(root -> sample(root, "ZERO").putNull("checkedBy"));
        rejectsDataset(root -> sample(root, "ZERO").putNull("reason"));
        rejectsDataset(root -> removeSample(root, "ZERO"));
        rejectsDataset(root -> removeSample(root, "INPUT"));
        rejectsDataset(root -> removeSample(root, "ENGINE"));
        rejectsDataset(root -> ((ObjectNode) array(root, "hints").get(1)).put("level", 2));
        rejectsCodec(root -> sample(root, "FULL").put("checkedBy", "123"));
        // 현재 계정·자격 조회 없이 과거 UUID만 보존한다.
        ObjectNode differentHistoricalAccount = complete();
        sample(differentHistoricalAccount, "ZERO")
                .put("checkedBy", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(
                        new FrozenDatasetValidator()
                                .validate(FrozenSnapshotCodec.freeze(differentHistoricalAccount))
                                .select("ZERO")
                                .report())
                .isNotNull();
    }

    @Test
    void kindDependentFixturesRequireExactControlsScoresAndInvalidReports() {
        rejectsDataset(root -> sample(root, "FULL").put("expectedScore", 99));
        rejectsDataset(root -> sample(root, "FULL").put("expectedSuccess", false));
        rejectsDataset(root -> expectedItem(root, "FULL", "METHOD").put("score", 19));
        rejectsDataset(root -> expectedItem(root, "FULL", "METHOD").put("requiredMet", false));
        rejectsDataset(root -> expectedItem(root, "FULL", "TIME").put("requiredMet", true));
        rejectsDataset(
                root ->
                        ((ArrayNode) sample(root, "FULL").get("expectData").get("items"))
                                .remove(0));
        rejectsDataset(root -> expectedItem(root, "FULL", "TIME").put("rubricCode", "METHOD"));
        rejectsDataset(root -> input(root, "INPUT").set("report", report("REPAIRED")));
        rejectsDataset(root -> input(root, "ENGINE").putNull("fault"));
        rejectsDataset(root -> input(root, "FULL").set("fault", fault()));
        rejectsDataset(root -> input(root, "INPUT").set("fault", fault()));
        rejectsDataset(
                root ->
                        ((ObjectNode) sample(root, "INPUT").get("expectData").get("error"))
                                .put("state", "SYSTEM_ERROR"));
        rejectsDataset(root -> sample(root, "ENGINE").put("expectedScore", 0));
        rejectsCodec(root -> ((ObjectNode) input(root, "ENGINE").get("fault")).put("failRuns", 2));
        rejectsCodec(
                root -> ((ObjectNode) input(root, "ENGINE").get("fault")).put("type", "OTHER"));
        rejectsCodec(root -> input(root, "INPUT").put("repair", true));
        rejectsCodec(
                root -> expectedItem(root, "FULL", "METHOD").put("score", new BigDecimal("20.01")));
    }

    @Test
    void inputErrorRetainsMissingNullTypesExactNumbersStringsAndArrayOrder() {
        List<JsonNode> malformed =
                List.of(
                        parse("{}"),
                        parse("{\"method\":null}"),
                        parse("{\"method\":1e0}"),
                        parse("{\"method\":1.0000000000000000000000000000000000000001}"),
                        parse("{\"method\":\"  원문\\r\\n \",\"unusual\":[2,1,null]}"),
                        parse("[null,\"report\",9007199254740993]"),
                        parse("null"));
        java.util.Set<String> payloadHashes = new java.util.HashSet<>();
        for (JsonNode raw : malformed) {
            ObjectNode root = complete();
            input(root, "INPUT").set("report", raw);
            FrozenSnapshot snapshot = FrozenSnapshotCodec.freeze(root);
            payloadHashes.add(snapshot.payloadHash());
            JsonNode retained =
                    sample((ObjectNode) snapshot.payload(), "INPUT").get("inputData").get("report");
            assertThat(SnapshotJson.encode(retained)).isEqualTo(SnapshotJson.encode(raw));
            var dataset =
                    new FrozenDatasetValidator()
                            .validate(FrozenSnapshotCodec.decode(snapshot.payloadBytes()));
            assertThat(dataset.select("INPUT").report()).isNull();
            assertThatThrownBy(
                            () -> FrozenModelProjection.project(dataset, dataset.select("INPUT")))
                    .hasMessage("INVALID_MODEL_PROJECTION");
        }
        assertThat(payloadHashes).hasSize(malformed.size());
        ObjectNode unknownPerson = complete();
        input(unknownPerson, "INPUT").set("report", report("미등록").put("culpritCode", "UNKNOWN"));
        assertThat(
                        new FrozenDatasetValidator()
                                .validate(FrozenSnapshotCodec.freeze(unknownPerson))
                                .select("INPUT")
                                .report())
                .isNull();
    }

    @Test
    void ownershipAndAllConservativeBindingsSurviveCallerMutation() {
        ObjectNode source = complete();
        FrozenSnapshot snapshot = FrozenSnapshotCodec.freeze(source);
        byte[] original = snapshot.payloadBytes();
        String hash = snapshot.payloadHash();
        source.removeAll();
        byte[] borrowed = snapshot.payloadBytes();
        borrowed[0] = 0;
        ((ObjectNode) snapshot.payload()).removeAll();
        assertThat(snapshot.payloadBytes()).isEqualTo(original);
        assertThat(snapshot.payloadHash()).isEqualTo(hash);
        byte[] incoming = original.clone();
        FrozenSnapshot decoded = FrozenSnapshotCodec.decode(incoming);
        incoming[0] = 0;
        assertThat(decoded.payloadBytes()).isEqualTo(original);
        var dataset = new FrozenDatasetValidator().validate(snapshot);
        ((ObjectNode) dataset.select("FULL").expectation()).removeAll();
        assertThat(dataset.select("FULL").expectation().get("kind").textValue())
                .isEqualTo("GRADED");
        var projection = FrozenModelProjection.project(dataset, dataset.select("FULL"));
        byte[] projected = projection.payloadBytes();
        projection.payloadBytes()[0] = 0;
        ((ObjectNode) projection.payload()).removeAll();
        assertThat(projection.payloadBytes()).isEqualTo(projected);
        List<Consumer<ObjectNode>> mutations =
                List.of(
                        root -> root.put("sourceRev", "1"),
                        root ->
                                ((ObjectNode) root.get("sections").get("reveal"))
                                        .put("revealText", "별도 공개 변경"),
                        root -> ((ObjectNode) array(root, "facts").get(0)).put("truth", "FALSE"),
                        root ->
                                ((ObjectNode) array(root, "persons").get(0))
                                        .put("secretText", "다른 비밀"),
                        root ->
                                ((ObjectNode) array(root, "clues").get(0))
                                        .put("sourceText", "다른 출처"),
                        root -> rubric(root, "METHOD").put("acceptedText", "다른 설명"),
                        root ->
                                ((ObjectNode)
                                                rubric(root, "METHOD")
                                                        .get("ruleData")
                                                        .get("claims")
                                                        .get(0))
                                        .put("meaning", "다른 의미"),
                        root ->
                                ((ObjectNode) array(root, "rubricClues").get(0))
                                        .put("linkText", "다른 연결"),
                        root ->
                                sample(root, "ZERO")
                                        .put("checkedBy", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        root -> sample(root, "ZERO").put("reason", "다른 근거"),
                        root -> expectedItem(root, "ZERO", "METHOD").put("reason", "다른 기대 근거"),
                        root ->
                                ((ObjectNode) input(root, "ENGINE").get("fault"))
                                        .put("type", "UNAVAILABLE"),
                        root -> ((ObjectNode) array(root, "roles").get(0)).putNull("brief"),
                        root ->
                                ((ObjectNode) array(root, "events").get(0))
                                        .put("actualText", "다른 사건"),
                        root -> {
                            ((ObjectNode) root.get("sections").get("basic")).put("difficulty", 5);
                            root.set(
                                    "policy",
                                    FrozenSnapshotCodec.createPolicy("RULE_20260924", 5, 900));
                        });
        GradeModels.Report selected = dataset.select("FULL").report();
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode changed = (ObjectNode) snapshot.payload();
            mutation.accept(changed);
            FrozenSnapshot other = FrozenSnapshotCodec.freeze(changed);
            assertThat(other.payloadHash()).isNotEqualTo(snapshot.payloadHash());
            assertThat(other.rubricHash()).isNotEqualTo(snapshot.rubricHash());
            assertThat(other.datasetHash()).isNotEqualTo(snapshot.datasetHash());
            assertThat(other.inputHash("FULL")).isNotEqualTo(snapshot.inputHash("FULL"));
            assertThat(other.reportHash(selected)).isNotEqualTo(snapshot.reportHash(selected));
        }
        ObjectNode reversedRule = (ObjectNode) snapshot.payload();
        reverse((ArrayNode) rubric(reversedRule, "METHOD").get("ruleData").get("levels"));
        assertThat(FrozenSnapshotCodec.freeze(reversedRule).payloadHash()).isNotEqualTo(hash);
        assertThatThrownBy(() -> snapshot.inputHash("MISSING"))
                .hasMessage("INVALID_FROZEN_SNAPSHOT");
        assertThatThrownBy(() -> snapshot.reportHash(null)).hasMessage("INVALID_FROZEN_SNAPSHOT");
    }

    @Test
    void strongModelCanariesExcludeEveryNonAllowlistedSurfaceButKeepApprovedContext() {
        ObjectNode source = complete();
        String[] canaries = {
            "ANSWER_CANARY",
            "REVEAL_CANARY",
            "OTHER_REPORT_CANARY",
            "EXPECTATION_CANARY",
            "REASON_CANARY",
            "EVENT_CANARY",
            "ROLE_CANARY",
            "HINT_CANARY",
            "BASIC_CANARY"
        };
        ObjectNode answer = (ObjectNode) source.get("sections").get("answer");
        answer.put("methodAnswer", canaries[0])
                .put("timeAnswer", canaries[0])
                .put("motiveAnswer", canaries[0]);
        ((ObjectNode) source.get("sections").get("reveal")).put("revealText", canaries[1]);
        input(source, "ZERO").set("report", report(canaries[2]));
        for (JsonNode row : array(source, "gradeSamples"))
            ((ObjectNode) row).put("reason", canaries[4]);
        expectedItem(source, "FULL", "METHOD").put("reason", canaries[3]);
        ((ObjectNode) array(source, "events").get(0)).put("actualText", canaries[5]);
        ((ObjectNode) array(source, "roles").get(0)).put("brief", canaries[6]);
        ((ObjectNode) array(source, "hints").get(0)).put("body", canaries[7]);
        ((ObjectNode) source.get("sections").get("basic")).put("intro", canaries[8]);
        ((ObjectNode) array(source, "persons").get(1)).put("secretText", "APPROVED_SECRET");
        ((ObjectNode) array(source, "facts").get(0)).put("basis", "APPROVED_FACT");
        rubric(source, "METHOD").put("acceptedText", "APPROVED_RUBRIC");
        var dataset = new FrozenDatasetValidator().validate(FrozenSnapshotCodec.freeze(source));
        var projected = FrozenModelProjection.project(dataset, dataset.select("FULL"));
        String bytes = new String(projected.payloadBytes(), StandardCharsets.UTF_8);
        for (String canary : canaries) assertThat(bytes).doesNotContain(canary);
        assertThat(bytes)
                .doesNotContain(
                        CHECKER,
                        "SELECTED_CULPRIT",
                        "correctCulpritCode",
                        "checkedBy",
                        "expectedScore",
                        "expectedSuccess",
                        "expectData",
                        "inputData",
                        "gradeSamples",
                        "fault",
                        "failRuns");
        assertThat(bytes)
                .contains(
                        "SELECTED_REPORT",
                        "APPROVED_SECRET",
                        "APPROVED_FACT",
                        "APPROVED_RUBRIC",
                        "CONTRADICT_CULPRIT",
                        "UNSUPPORTED_ACCOMPLICE");
        assertThat(projected.payload().get("report")).isEqualTo(report("SELECTED_REPORT"));
        assertThat(dataset.gradingSnapshot().correctCulpritCode()).isEqualTo("P1");
        assertThat(
                        dataset.frozenSnapshot()
                                .payload()
                                .get("resources")
                                .get("rubrics")
                                .get(0)
                                .get("ruleData")
                                .get("claims"))
                .hasSize(1);
        var engineProjection = FrozenModelProjection.project(dataset, dataset.select("ENGINE"));
        assertThat(new String(engineProjection.payloadBytes(), StandardCharsets.UTF_8))
                .doesNotContain("fault", "failRuns", "TIMEOUT", "SYSTEM_ERROR");
    }

    @Test
    void contractualTextAndStructuredJsonBoundsRejectWithoutPrimitiveCap() {
        rejectsCodec(
                root ->
                        ((ObjectNode) root.get("sections").get("basic"))
                                .put("title", "😀".repeat(161)));
        rejectsCodec(root -> ((ObjectNode) array(root, "persons").get(0)).put("name", " "));
        rejectsCodec(root -> ((ObjectNode) root.get("sections").get("basic")).put("estMin", 32768));
        rejectsCodec(
                root ->
                        ((ObjectNode) input(root, "FULL").get("report"))
                                .put("method", "a".repeat(5001)));
        rejectsCodec(
                root -> ((ObjectNode) input(root, "FULL").get("report")).put("method", "a\r\nb"));
        rejectsCodec(
                root ->
                        input(root, "INPUT")
                                .set(
                                        "report",
                                        parse("{\"method\":\"" + "a".repeat(131072) + "\"}")));
        rejectsCodec(root -> input(root, "INPUT").set("report", parse("{\"number\":1e131072}")));
        rejectsCodec(
                root -> {
                    ArrayNode raw = JsonNodeFactory.instance.arrayNode();
                    for (int index = 0; index < 44000; index++) raw.add(0);
                    // compact는128KiB 이하지만 JSONB의 쉼표 공백을 포함하면 상한을 넘는다.
                    assertThat(SnapshotJson.encode(raw).length).isLessThan(131072);
                    input(root, "INPUT").set("report", raw);
                });
        rejectsCodec(
                root -> {
                    ArrayNode items =
                            ((ObjectNode) sample(root, "FULL").get("expectData")).putArray("items");
                    for (int index = 0; index < 50; index++) {
                        items.add(
                                object().put("rubricCode", "ITEM_" + index)
                                        .put("score", 0)
                                        .putNull("requiredMet")
                                        .put("reason", "\u0001".repeat(1000)));
                    }
                });
        rejectsCodec(
                root -> {
                    ArrayNode claims =
                            ((ObjectNode) rubric(root, "METHOD").get("ruleData"))
                                    .putArray("claims");
                    for (int index = 0; index < 20; index++) {
                        ObjectNode claim =
                                object().put("code", "CLAIM_" + index)
                                        .put("meaning", "\u0001".repeat(1000));
                        ArrayNode facts = claim.putArray("factCodes");
                        for (int fact = 0; fact < 20; fact++)
                            facts.add("F".repeat(29) + (100 + fact));
                        claim.putArray("exampleClueRoutes");
                        claims.add(claim);
                    }
                });
        ObjectNode exactLongNumber = complete();
        input(exactLongNumber, "INPUT")
                .set(
                        "report",
                        parse("{\"method\":12345678901234567890123456789012345678901234567890}"));
        assertThat(
                        new FrozenDatasetValidator()
                                .validate(FrozenSnapshotCodec.freeze(exactLongNumber))
                                .select("INPUT")
                                .report())
                .isNull();
        ObjectNode nullable = complete();
        ((ObjectNode) array(nullable, "persons").get(0)).putNull("publicText");
        assertThat(
                        FrozenSnapshotCodec.freeze(nullable)
                                .payload()
                                .get("resources")
                                .get("persons")
                                .get(0)
                                .get("publicText")
                                .isNull())
                .isTrue();
        rejectsCodec(root -> ((ObjectNode) array(root, "persons").get(0)).remove("publicText"));
    }

    /** 모든 종류/등록 단계를 가진 합성 서버 원고이며 실제 의미 승인이나 사람 실행 증거가 아니다. */
    static ObjectNode complete() {
        ObjectNode root = empty();
        ObjectNode sections = (ObjectNode) root.get("sections");
        ((ObjectNode) sections.get("answer"))
                .put("culpritCode", "P1")
                .put("methodAnswer", "답안")
                .put("timeAnswer", "시간답안")
                .put("motiveAnswer", "동기답안");
        ((ObjectNode) sections.get("basic")).put("intro", "도입").put("setting", "배경");
        ((ObjectNode) sections.get("reveal")).put("revealText", "공개");
        for (String code : List.of("P1", "P2"))
            array(root, "persons")
                    .add(
                            object().put("code", code)
                                    .put("name", code)
                                    .putNull("publicText")
                                    .put("secretText", "비밀"));
        for (String code : List.of("R1", "R2", "R3"))
            array(root, "roles")
                    .add(object().put("code", code).put("name", code).put("brief", "역할"));
        array(root, "pairs")
                .add(object().put("roleA", "R1").put("roleB", "R2"))
                .add(object().put("roleA", "R1").put("roleB", "R3"));
        for (String code : List.of("C1", "C2"))
            array(root, "clues")
                    .add(
                            object().put("code", code)
                                    .put("title", code)
                                    .put("body", "단서")
                                    .put("personCode", "P1")
                                    .put("scope", "ROLE")
                                    .put("sourceText", "출처"));
        array(root, "clueRoles")
                .add(object().put("clueCode", "C1").put("roleCode", "R1"))
                .add(object().put("clueCode", "C2").put("roleCode", "R2"));
        array(root, "hints")
                .add(object().put("code", "H1").put("level", 2).putNull("body"))
                .add(object().put("code", "H2").put("level", 1).put("body", "힌트"));
        array(root, "events")
                .add(
                        object().put("code", "E1")
                                .putNull("startMin")
                                .putNull("endMin")
                                .put("actualText", "사건")
                                .putNull("apparentText"))
                .add(
                        object().put("code", "E2")
                                .put("startMin", 0)
                                .put("endMin", 1)
                                .putNull("actualText")
                                .put("apparentText", "보이는 사건"));
        array(root, "facts")
                .add(
                        object().put("code", "F1")
                                .put("statement", "사실")
                                .put("truth", "TRUE")
                                .put("basis", "근거"))
                .add(
                        object().put("code", "F2")
                                .putNull("statement")
                                .put("truth", "MISREAD")
                                .putNull("basis"));
        array(root, "rubrics")
                .add(rubric("CULPRIT", 25, true))
                .add(rubric("METHOD", 20, true))
                .add(rubric("TIME", 15, false))
                .add(rubric("MOTIVE", 10, false))
                .add(rubric("EVIDENCE", 30, true));
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            array(root, "rubricClues")
                    .add(
                            object().put("rubricCode", code)
                                    .put("clueCode", "C1")
                                    .put("linkText", "연결"));
        }
        array(root, "gradeSamples")
                .add(graded("FULL", true))
                .add(graded("ZERO", false))
                .add(error("INPUT", "INPUT_ERROR"))
                .add(error("ENGINE", "ENGINE_ERROR"));
        return root;
    }

    private static ObjectNode empty() {
        ObjectNode root =
                object().put("formatNo", 1)
                        .put("storyCode", "ST_TEST")
                        .put("versionNo", 1)
                        .put("sourceRev", "0");
        root.set("policy", FrozenSnapshotCodec.createPolicy("RULE_20260924", 3, 900));
        ObjectNode sections = object();
        sections.set(
                "basic",
                object().put("title", "제목")
                        .putNull("intro")
                        .putNull("setting")
                        .put("difficulty", 3)
                        .putNull("estMin")
                        .putNull("estMax")
                        .put("limitSec", 900)
                        .putNull("timelineOrigin"));
        sections.set(
                "answer",
                object().putNull("culpritCode")
                        .putNull("methodAnswer")
                        .putNull("timeAnswer")
                        .putNull("motiveAnswer"));
        sections.set("reveal", object().putNull("revealText"));
        root.set("sections", sections);
        ObjectNode resources = object();
        RESOURCES.forEach(resources::putArray);
        root.set("resources", resources);
        return root;
    }

    private static ObjectNode rubric(String category, int max, boolean required) {
        ObjectNode row =
                object().put("code", category)
                        .put("category", category)
                        .put("maxScore", max)
                        .put("requiredYn", required)
                        .putNull("passScore")
                        .putNull("acceptedText")
                        .putNull("partialText")
                        .putNull("rejectText");
        if (required) row.put("passScore", max);
        if (category.equals("CULPRIT")) {
            row.set("ruleData", parse(CULPRIT_RULE));
        } else {
            ObjectNode rule = object().put("formatNo", 1).putNull("requiredNotice");
            if (required) rule.put("requiredNotice", "입증하세요");
            ObjectNode claim = object().put("code", "CLAIM").put("meaning", "정의한 의미");
            claim.putArray("factCodes").add("F1");
            claim.putArray("exampleClueRoutes").addArray().add("C1");
            rule.putArray("claims").add(claim);
            ObjectNode zero = object().put("code", "ZERO").put("score", 0);
            zero.putArray("routes");
            ObjectNode full = object().put("code", "FULL").put("score", max);
            full.putArray("routes").addArray().add("CLAIM");
            rule.putArray("levels").add(zero).add(full);
            rule.putArray("contradictions");
            row.set("ruleData", rule);
        }
        return row;
    }

    private static ObjectNode graded(String code, boolean full) {
        ObjectNode row =
                sampleRow(code).put("expectedScore", full ? 100 : 0).put("expectedSuccess", full);
        ObjectNode input = object().put("formatNo", 1).putNull("fault");
        input.set("report", report(full ? "SELECTED_REPORT" : "OTHER_REPORT"));
        row.set("inputData", input);
        ObjectNode expect = object().put("formatNo", 1).put("kind", "GRADED").putNull("error");
        ArrayNode items = expect.putArray("items");
        int[] max = {25, 20, 15, 10, 30};
        String[] categories = {"CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE"};
        for (int index = 0; index < categories.length; index++) {
            ObjectNode item =
                    object().put("rubricCode", categories[index])
                            .put("score", full ? max[index] : 0)
                            .putNull("requiredMet")
                            .put("reason", "기대 근거");
            if (index == 0 || index == 1 || index == 4) item.put("requiredMet", full);
            items.add(item);
        }
        row.set("expectData", expect);
        return row;
    }

    private static ObjectNode error(String code, String kind) {
        ObjectNode row = sampleRow(code).putNull("expectedScore").putNull("expectedSuccess");
        ObjectNode input = object().put("formatNo", 1).putNull("fault");
        input.set(
                "report",
                kind.equals("INPUT_ERROR") ? object().putNull("method") : report("ENGINE_REPORT"));
        if (kind.equals("ENGINE_ERROR")) input.set("fault", fault());
        row.set("inputData", input);
        ObjectNode expect = object().put("formatNo", 1).put("kind", kind).putNull("items");
        expect.set(
                "error",
                object().put(
                                "code",
                                kind.equals("INPUT_ERROR")
                                        ? "INVALID_REPORT"
                                        : "GRADING_UNAVAILABLE")
                        .put("state", kind.equals("INPUT_ERROR") ? "REJECTED" : "SYSTEM_ERROR")
                        .putNull("score")
                        .put("attemptDelta", 0));
        row.set("expectData", expect);
        return row;
    }

    private static ObjectNode sampleRow(String code) {
        return object().put("code", code).put("reason", "사례 근거").put("checkedBy", CHECKER);
    }

    private static ObjectNode report(String method) {
        return object().put("culpritCode", "P1")
                .put("method", method)
                .put("time", "")
                .put("motive", "")
                .put("evidence", "");
    }

    private static ObjectNode fault() {
        return object().put("type", "TIMEOUT").put("failRuns", 3);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static JsonNode parse(String json) {
        return SnapshotJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ArrayNode array(ObjectNode root, String resource) {
        return (ArrayNode) root.get("resources").get(resource);
    }

    private static ObjectNode rubric(ObjectNode root, String code) {
        return find(array(root, "rubrics"), code);
    }

    private static ObjectNode sample(ObjectNode root, String code) {
        return find(array(root, "gradeSamples"), code);
    }

    private static ObjectNode input(ObjectNode root, String code) {
        return (ObjectNode) sample(root, code).get("inputData");
    }

    private static ObjectNode expectedItem(ObjectNode root, String sampleCode, String rubricCode) {
        for (JsonNode item : sample(root, sampleCode).get("expectData").get("items")) {
            if (rubricCode.equals(item.get("rubricCode").textValue())) return (ObjectNode) item;
        }
        throw new AssertionError("MISSING_TEST_ITEM");
    }

    private static ObjectNode find(ArrayNode rows, String code) {
        for (JsonNode row : rows)
            if (code.equals(row.get("code").textValue())) return (ObjectNode) row;
        throw new AssertionError("MISSING_TEST_ROW");
    }

    private static void removeSample(ObjectNode root, String code) {
        ArrayNode samples = array(root, "gradeSamples");
        for (int index = 0; index < samples.size(); index++) {
            if (code.equals(samples.get(index).get("code").textValue())) {
                samples.remove(index);
                return;
            }
        }
    }

    private static void reverse(ArrayNode rows) {
        java.util.ArrayList<JsonNode> copy = new java.util.ArrayList<>();
        rows.forEach(copy::add);
        java.util.Collections.reverse(copy);
        rows.removeAll();
        copy.forEach(rows::add);
    }

    private static void rejectsCodec(Consumer<ObjectNode> mutation) {
        ObjectNode root = complete();
        mutation.accept(root);
        assertThatThrownBy(() -> FrozenSnapshotCodec.freeze(root))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
    }

    private static void rejectsDataset(Consumer<ObjectNode> mutation) {
        ObjectNode root = complete();
        mutation.accept(root);
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(root);
        assertThatThrownBy(() -> new FrozenDatasetValidator().validate(frozen))
                .hasMessage("INVALID_FROZEN_DATASET")
                .hasNoCause();
    }

    private static void rejectsWire(String wire) {
        assertThatThrownBy(() -> FrozenSnapshotCodec.decode(wire.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
    }
}
