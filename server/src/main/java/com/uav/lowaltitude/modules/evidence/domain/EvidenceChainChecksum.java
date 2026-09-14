package com.uav.lowaltitude.modules.evidence.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** C07 链完整性：规范串 SHA-256。算法与契约 docs/backend-stage9/evidence-chain-api-contract.md 锁定。 */
public final class EvidenceChainChecksum {
    public static final String ALGORITHM = "SHA-256";
    public static final int TYPE_LIMIT = 100;

    private EvidenceChainChecksum() { }

    public static String fingerprintTrack(String layer, Long endedAt, int points) {
        return "layer=" + n(layer) + "|ended=" + n(endedAt) + "|points=" + points;
    }

    public static String fingerprintFile(String sha256, String status) {
        return "sha256=" + n(sha256) + "|status=" + n(status);
    }

    public static String fingerprintAlarm(Long receivedAt, String severity) {
        return "received=" + n(receivedAt) + "|severity=" + n(severity);
    }

    public static String fingerprintJudgment(Long assessedAt, String conclusion) {
        return "assessed=" + n(assessedAt) + "|conclusion=" + n(conclusion);
    }

    public static String fingerprintAuthorization(String status, String authorizationId) {
        return "status=" + n(status) + "|auth=" + n(authorizationId);
    }

    public static String fingerprintVerification(long version, String conclusion) {
        return "version=" + version + "|conclusion=" + n(conclusion);
    }

    public static String fingerprintHandoff(String delivery, long sourceVersion) {
        return "delivery=" + n(delivery) + "|version=" + sourceVersion;
    }

    public static String fingerprintAudit(String action, Long occurredAt) {
        return "action=" + n(action) + "|at=" + n(occurredAt);
    }

    public static String digest(List<Member> members) {
        List<String> lines = new ArrayList<>(members.size());
        for (Member member : members) {
            lines.add(member.recordType() + "|" + member.recordId() + "|" + member.fingerprint());
        }
        lines.sort(Comparator.naturalOrder());
        String canonical = String.join("\n", lines);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String fileRecordType(String kindCode) {
        if (kindCode == null) return null;
        return switch (kindCode) {
            case "EO_VIDEO" -> "VIDEO";
            case "EO_STILL", "SCENE_PHOTO", "TRACK_SNAPSHOT" -> "IMAGE";
            case "COMMAND_LOG" -> "AUTHORIZATION";
            case "NOTICE_RECEIPT", "PENALTY_DOCUMENT" -> "DISPOSAL";
            case "COMMISSION_REPORT" -> "OPERATION";
            default -> null;
        };
    }

    public static boolean fileBroken(String status) {
        return "MISSING".equals(status) || "CORRUPT".equals(status);
    }

    public static boolean fileAvailable(String status) {
        return "AVAILABLE".equals(status);
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    private static String n(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    public record Member(String recordType, String recordId, String fingerprint) { }
}
