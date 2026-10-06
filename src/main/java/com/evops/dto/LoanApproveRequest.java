package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LoanApproveRequest {

    @NotBlank(message = "审批角色不能为空")
    private String approverRole;

    @NotBlank(message = "审批人不能为空")
    private String approverName;

    /** APPROVE 通过 / REJECT 拒绝 */
    @NotBlank(message = "审批决定不能为空")
    private String decision;

    private String opinion;
}
