package com.uav.lowaltitude.integration.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator.Dataset;

/** 回放信封校验：哈希不符、record_no 重复、结构缺失一律拒绝；同一数据集两次生成逐字节相同。 */
class FusionReplayReaderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FusionReplayReader reader = new FusionReplayReader(json);
    private final FusionReplayDatasetGenerator generator = new FusionReplayDatasetGenerator();
    private final FusionReplayRunner runner = new FusionReplayRunner(generator, reader, null, null, json);

    @Test
    void generatedEnvelopesAreAcceptedAndCarryContractIdentity() {
        List<String> lines = runner.ndjson();
        assertThat(lines).isNotEmpty();
        List<FusionReplayReader.Envelope> envelopes = reader.readAll(lines);
        assertThat(envelopes).hasSameSizeAs(lines);
        FusionReplayReader.Envelope first = envelopes.get(0);
        assertThat(first.datasetId()).isEqualTo(FusionReplayDatasetGenerator.DATASET_ID);
        assertThat(first.source()).isEqualTo("replay:" + first.sourceCode() + ":" + FusionReplayDatasetGenerator.DATASET_ID);
        assertThat(first.payloadHash()).matches("^[0-9a-f]{64}$");
        assertThat(envelopes).extracting(FusionReplayReader.Envelope::sourceCode)
                .containsAnyOf(FusionReplayDatasetGenerator.RADAR, FusionReplayDatasetGenerator.TDOA, FusionReplayDatasetGenerator.EO);
    }

    @Test
    void datasetIsByteIdenticalAcrossRuns() {
        // 固定种子与固定 T0：回放回归的前提是"同一数据集重放得到同一结论"。
        assertThat(runner.ndjson()).isEqualTo(runner.ndjson());
        Dataset a = generator.generate(), b = generator.generate();
        assertThat(a.records()).hasSameSizeAs(b.records());
        assertThat(a.groundTruth()).hasSameSizeAs(b.groundTruth());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String line = runner.ndjson().get(0);
        String tampered = line.replaceFirst("\"record_no\":\\d+", "\"record_no\":9999");
        assertThatThrownBy(() -> reader.read(tampered)).isInstanceOf(IllegalStateException.class).hasMessageContaining("哈希不符");
        String badHash = line.replaceFirst("\"payload_hash\":\"[0-9a-f]{64}\"", "\"payload_hash\":\"" + "0".repeat(64) + "\"");
        assertThatThrownBy(() -> reader.read(badHash)).isInstanceOf(IllegalStateException.class).hasMessageContaining("哈希不符");
    }

    @Test
    void duplicateRecordNumbersAndMalformedRecordsAreRejected() {
        String line = runner.ndjson().get(0);
        assertThatThrownBy(() -> reader.readAll(List.of(line, line))).isInstanceOf(IllegalStateException.class).hasMessageContaining("record_no 重复");
        assertThatThrownBy(() -> reader.read("{not-json")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.read("{\"dataset_id\":\"d\",\"record_no\":1}")).isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少字段");
    }
}
