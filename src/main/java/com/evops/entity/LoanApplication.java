package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物借阅申请单：首次借阅（INITIAL）与归还延期（EXTENSION）共表。
 * 审批中 APPROVING -> 已通过 APPROVED / 已拒绝 REJECTED（拒绝封存只读）。
 * 保存等级、保险资料在申请时固化快照；延期单通过 parentId 挂到首借单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_application")
public class LoanApplication extends BaseEntity {

    /** 申请单号，全局唯一 */
    private String applyNo;

    private Long relicId;

    /** 受理馆区（展厅 id）：审批人只能查看本馆区申请的完整保险资料 */
    private Long hallId;

    /** INITIAL 首次借阅 / EXTENSION 归还延期 */
    private String appType;

    /** 延期单指向首借单；首借单为空 */
    private Long parentId;

    /** 申请时文物保存等级快照（ONE/TWO/THREE），决定审批链 */
    private String grade;

    /** 借阅场景：EXHIBITION / RESEARCH / PHOTOGRAPHY */
    private String scenario;

    /** 申请借阅天数（延期单为延期天数） */
    private Integer borrowDays;

    /** 申请人 */
    private String applicant;

    /** 用途/延期原因 */
    private String purpose;

    /** 保险资料：保险金额 / 承保公司 / 保单号（按馆区脱敏） */
    private BigDecimal insuranceAmount;

    private String insurer;

    private String policyNo;

    /** 审批通过时计算的应还时间 */
    private LocalDateTime expectedReturnAt;

    /** APPROVING / APPROVED / REJECTED */
    private String status;

    /** 审批通过后贯通的 t_relic_loan 借出单 id */
    private Long loanId;

    /** 通过或拒绝的时间；APPROVING 时为空 */
    private LocalDateTime closedAt;
}
