package com.uav.lowaltitude.modules.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.modules.evidence.api.EvidenceTestFiles;
import com.uav.lowaltitude.platform.api.ApiException;

class EvidenceFileTypesTest {
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
    private static final byte[] HTML = "<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

    @Test
    void storedTypeComesFromContentNotFromTheClientDeclaration() {
        byte[] jpeg = EvidenceTestFiles.bytes("a.jpg");
        assertThat(EvidenceFileTypes.verify("EO_STILL", "现场.JPG", "application/octet-stream", jpeg)).isEqualTo("image/jpeg");
        assertThat(EvidenceFileTypes.verify("EO_STILL", "a.jpeg", "image/jpg", jpeg)).isEqualTo("image/jpeg");
        assertThat(EvidenceFileTypes.verify("SCENE_PHOTO", "a.png", null, EvidenceTestFiles.bytes("a.png"))).isEqualTo("image/png");
        assertThat(EvidenceFileTypes.verify("EO_VIDEO", "a.webm", "video/webm;codecs=vp8", EvidenceTestFiles.bytes("a.webm"))).isEqualTo("video/webm");
        assertThat(EvidenceFileTypes.verify("EO_VIDEO", "a.mp4", "video/mp4", EvidenceTestFiles.bytes("a.mp4"))).isEqualTo("video/mp4");
        assertThat(EvidenceFileTypes.verify("COMMISSION_REPORT", "a.pdf", "application/pdf", EvidenceTestFiles.bytes("a.pdf"))).isEqualTo("application/pdf");
        assertThat(EvidenceFileTypes.verify("TRACK_SNAPSHOT", "a.json", "application/json", EvidenceTestFiles.bytes("a.json"))).isEqualTo("application/json");
        // Windows 把 .csv 声明成 Excel 类型；文本日志里是 JSON 也仍按扩展名记为文本。
        assertThat(EvidenceFileTypes.verify("COMMAND_LOG", "a.csv", "application/vnd.ms-excel", EvidenceTestFiles.bytes("a.csv"))).isEqualTo("text/csv");
        assertThat(EvidenceFileTypes.verify("COMMAND_LOG", "a.txt", "text/plain", "{\"code\":200}".getBytes(StandardCharsets.UTF_8))).isEqualTo("text/plain");
    }

    @Test
    void realPreviewSamplesAreRecognised() throws Exception {
        assertThat(EvidenceFileTypes.detect(resource("/evidence/preview-vp8.webm"))).isEqualTo("video/webm");
        assertThat(EvidenceFileTypes.detect(resource("/evidence/preview-document.pdf"))).isEqualTo("application/pdf");
        assertThat(EvidenceFileTypes.detect(EvidenceTestFiles.bytes("a.webp"))).isEqualTo("image/webp");
        // 本地编码（GBK）的文本日志也算文本，不按乱码拒收。
        assertThat(EvidenceFileTypes.detect("指令回执 200 成功\r\n".getBytes(Charset.forName("GBK")))).isEqualTo("text/plain");
    }

    @Test
    void programsAndWebPagesAreNotAcceptedAsPhotos() {
        rejected(() -> EvidenceFileTypes.verify("SCENE_PHOTO", "tool.exe", "application/x-msdownload", EXE),
                "EVIDENCE_TYPE_NOT_ALLOWED", "“现场照片”只收 JPG 图片、PNG 图片、WEBP 图片，不能上传 .exe 文件。");
        rejected(() -> EvidenceFileTypes.verify("SCENE_PHOTO", "page.html", "text/html", HTML),
                "EVIDENCE_TYPE_NOT_ALLOWED", "不能上传 .html 文件");
        rejected(() -> EvidenceFileTypes.verify("SCENE_PHOTO", "photo", "image/jpeg", EvidenceTestFiles.bytes("a.jpg")),
                "EVIDENCE_TYPE_NOT_ALLOWED", "这个文件没有扩展名");
        rejected(() -> EvidenceFileTypes.verify("SCENE_PHOTO", "tool.jpg", "image/jpeg", EXE),
                "EVIDENCE_TYPE_MISMATCH", "文件内容不是有效的 JPG 图片");
        rejected(() -> EvidenceFileTypes.verify("SCENE_PHOTO", "page.jpg", "image/jpeg", HTML),
                "EVIDENCE_TYPE_MISMATCH", "扩展名是 .jpg，实际是网页文件");
        // 网页改名成文本也不收：下载时虽按附件给出，但不该进证据库。
        rejected(() -> EvidenceFileTypes.verify("COMMAND_LOG", "log.txt", "text/plain", HTML),
                "EVIDENCE_TYPE_MISMATCH", "实际是网页文件");
        rejected(() -> EvidenceFileTypes.verify("EO_VIDEO", "clip.mp4", "video/mp4", "not a video".getBytes(StandardCharsets.UTF_8)),
                "EVIDENCE_TYPE_MISMATCH", "扩展名是 .mp4，实际是 TXT 文本");
        rejected(() -> EvidenceFileTypes.verify("EO_VIDEO", "clip.mp4", "video/mp4", EXE),
                "EVIDENCE_TYPE_MISMATCH", "文件内容不是有效的 MP4 视频，可能改过扩展名或文件已损坏");
    }

    @Test
    void renamedJpegIsRejectedWhateverTheClientDeclares() {
        byte[] jpeg = EvidenceTestFiles.bytes("a.jpg");
        for (String declared : new String[]{"image/png", "image/jpeg", null}) {
            rejected(() -> EvidenceFileTypes.verify("EO_STILL", "renamed.png", declared, jpeg),
                    "EVIDENCE_TYPE_MISMATCH", "文件内容与扩展名不符：扩展名是 .png，实际是 JPG 图片");
        }
        rejected(() -> EvidenceFileTypes.verify("EO_STILL", "a.jpg", "image/png", jpeg),
                "EVIDENCE_TYPE_MISMATCH", "上传时声明的文件类型与实际内容（JPG 图片）不符");
        rejected(() -> EvidenceFileTypes.verify("TRACK_SNAPSHOT", "a.json", "application/json", "{\"a\":1} trailing".getBytes(StandardCharsets.UTF_8)),
                "EVIDENCE_TYPE_MISMATCH", "实际是 TXT 文本");
    }

    @Test
    void truncatedOrForeignContainersAreNotRecognised() {
        byte[] mp4 = EvidenceTestFiles.bytes("a.mp4");
        assertThat(EvidenceFileTypes.detect(java.util.Arrays.copyOf(mp4, mp4.length - 2))).isNull();
        byte[] mkv = EvidenceTestFiles.bytes("a.webm");
        mkv[8] = 'm'; mkv[9] = 'a'; mkv[10] = 't'; mkv[11] = 'r';
        assertThat(EvidenceFileTypes.detect(mkv)).isNull();
        // 没有结尾标记、带压缩数据的半截 PDF。
        byte[] head = "%PDF-1.7\n1 0 obj\n<< /Length 3 >>\nstream\n".getBytes(StandardCharsets.US_ASCII);
        byte[] truncatedPdf = java.util.Arrays.copyOf(head, head.length + 3);
        truncatedPdf[head.length] = 0x78; truncatedPdf[head.length + 1] = (byte) 0x9C; truncatedPdf[head.length + 2] = 0;
        assertThat(EvidenceFileTypes.detect(truncatedPdf)).isNull();
        assertThat(EvidenceFileTypes.detect(new byte[0])).isNull();
    }

    @Test
    void downloadNeverEchoesUnsafeLegacyDeclarations() {
        assertThat(EvidenceFileTypes.downloadType("image/jpeg")).isEqualTo("image/jpeg");
        assertThat(EvidenceFileTypes.downloadType("text/plain; charset=UTF-8")).isEqualTo("text/plain");
        assertThat(EvidenceFileTypes.downloadType("text/html")).isEqualTo("application/octet-stream");
        assertThat(EvidenceFileTypes.downloadType("image/svg+xml")).isEqualTo("application/octet-stream");
        assertThat(EvidenceFileTypes.downloadType("application/x-msdownload")).isEqualTo("application/octet-stream");
        assertThat(EvidenceFileTypes.downloadType(null)).isEqualTo("application/octet-stream");
    }

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getMessage()).contains(message);
        });
    }

    private byte[] resource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return stream.readAllBytes();
        }
    }
}
