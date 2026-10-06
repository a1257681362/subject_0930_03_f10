package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 借阅快照：审批通过时生成，只追加不改写。
 * 固化文物（编号/名称/等级/材质）、场景、时长、保险资料与完整审批链，
 * 后续等级调整、保险变更、申请单状态流转都不改写已生成快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_snapshot")
public class LoanSnapshot extends BaseEntity {

    /** 快照单号，全局唯一（申请单号 + "-SNAP"） */
    private String snapshotNo;

    private Long applicationId;

    /** 贯通的 t_relic_loan 借出单 id */
    private Long loanId;

    private Long relicId;

    private String relicNo;

    private String relicName;

    /** 通过时保存等级快照 */
    private String grade;

    private String material;

    /** 借阅场景 */
    private String scenario;

    /** 批准天数（延期快照为延期天数） */
    private Integer approvedDays;

    private String applicant;

    /** 保险资料快照 */
    private BigDecimal insuranceAmount;

    private String insurer;

    private String policyNo;

    /** 受理馆区 */
    private Long hallId;

    /** 审批链留痕，如 "CURATOR:张三->DIRECTOR:李馆长" */
    private String approvalChain;

    /** 应还时间 */
    private LocalDateTime expectedReturnAt;
}
