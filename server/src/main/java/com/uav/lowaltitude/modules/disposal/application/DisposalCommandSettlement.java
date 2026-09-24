package com.uav.lowaltitude.modules.disposal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.uav.lowaltitude.modules.device.application.DeviceCommandFinished;

/** 设备指令提交成功后再结案，避免和正在写指令的事务互相等待。 */
@Component
public class DisposalCommandSettlement {
    private static final Logger log = LoggerFactory.getLogger(DisposalCommandSettlement.class);
    private final DisposalReceiptSync receipts;

    public DisposalCommandSettlement(DisposalReceiptSync receipts) { this.receipts = receipts; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommandFinished(DeviceCommandFinished event) {
        try {
            receipts.syncByCommand(event.commandId());
        } catch (RuntimeException ex) {
            log.warn("device command {} finished but disposal receipt was not applied: {}", event.commandId(), ex.toString());
        }
    }
}
