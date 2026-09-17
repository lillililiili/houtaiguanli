package com.uav.lowaltitude.modules.flight.api;

import java.util.List;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;

public final class FlightVerificationDtos {
    private FlightVerificationDtos() { }
    public record VerifyRequest(String conclusion, String evidence, String note, Long expectedRevision) { }
    public record AutomaticRequest(Long expectedRevision) { }
    public record FeedbackRequest(String verificationId, String recipientId) { }
    public record Verification(String verificationId, String planId, long revisionNo, String conclusion,
            String takeoffStatus, String evidence, String note, String handledBy, String handledByName, long handledAt) { }
    public record Feedback(String feedbackId, String verificationId, String planId, String recipientId,
            String recipientName, String deliveryStatus, String receiptStatus, String processingResult,
            String blockedReason, long createdAt, Long submittedAt, Long deliveredAt, Long acknowledgedAt,RecipientSnapshot recipientSnapshot) { }
    public record Workflow(String planId, long revision, String recipientId, String recipientName,
            boolean canVerify, String verificationBlocker, boolean canFeedback,
            List<Verification> verifications, List<Feedback> feedback,RecipientSnapshot recipientSnapshot,String recipientBlockedReason) { }
}
