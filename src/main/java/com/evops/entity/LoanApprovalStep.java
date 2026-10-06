package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 借阅审批记录：只追加。逐步记录应签角色、实签人、决定、意见与签署时间。
 * 审批链由申请时等级快照按配置决定，高风险等级链尾追加馆长 DIRECTOR。
 */
@Data
@TableName("t_loan_approval_step")
public class LoanApprovalStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;

    /** 审批步骤号，申请单内从 1 递增，与审批链一一对应 */
    private Integer stepNo;

    /** 应签角色：CURATOR 馆藏保管员 / DIRECTOR 馆长 */
    private String approverRole;

    /** 实签人 */
    private String approverName;

    /** APPROVE 通过 / REJECT 拒绝 */
    private String decision;

    /** 审批意见 */
    private String opinion;

    /** 签署时间 */
    private LocalDateTime signedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
