package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 补偿记录：只追加。修复部分成功（CONDITIONAL 验收）时生成——
 * 保护等级补偿（GRADE）与借阅申请补偿（LOAN）成对登记、彼此对应：
 * 同一次补偿事件共享 compensate_no，LOAN 行 pair_id 指向 GRADE 行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_compensation")
public class LoanCompensation extends BaseEntity {

    /** 补偿单号（"CP-" + 修复项目号），同次事件的 GRADE/LOAN 行共享 */
    private String compensateNo;

    /** GRADE 保护等级补偿 / LOAN 借阅申请补偿 */
    private String side;

    /** LOAN 行指向对应 GRADE 行 id；GRADE 行为空 */
    private Long pairId;

    private Long relicId;

    /** 触发补偿的修复项目 */
    private Long projectId;

    /** 受影响的借阅申请单（仅 LOAN 侧） */
    private Long applicationId;

    /** 保护等级补偿：调整前/后等级 */
    private String fromGrade;

    private String toGrade;

    /** 借阅申请补偿：压缩前/后天数 */
    private Integer fromDays;

    private Integer toDays;

    /** CLAMP_DAYS 时长压缩 / NO_CHANGE 无需调整 / ADJUST_GRADE 等级调整（GRADE 侧） */
    private String action;

    /** 补偿说明 */
    private String detail;
}
