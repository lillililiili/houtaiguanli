package com.uav.lowaltitude.modules.handoff.domain;

/**
 * 交接域看"处置是否已完成"的只读窗口。
 *
 * 接口留在 handoff 侧、由 disposal 侧实现：交接只需要知道"有没有完成的授权"这一个事实，
 * 不该反向依赖处置域的仓库与行结构——那会让两个域的读写路径缠在一起，日后任一侧改表都要动另一侧。
 */
public interface DisposalCompletionPort {

    /** 该主体是否存在状态为 COMPLETED 的处置授权。 */
    boolean completedExists(String subjectKind, String subjectId);

    /** 干扰已完成、且还没有处罚交接的无人机事件。 */
    java.util.List<String> jammingCompletedWithoutPunishment();

    /** 该事件最新一条已完成干扰的申请人，用作自动交接的提交人外键。 */
    String completedJammingRequester(String eventId);
}
