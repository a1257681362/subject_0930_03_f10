package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class LoanExtendRequest {

    @NotBlank(message = "延期申请单号不能为空")
    private String applyNo;

    @NotNull(message = "延期天数不能为空")
    @Min(value = 1, message = "延期天数至少 1 天")
    private Integer extendDays;

    @NotBlank(message = "延期原因不能为空")
    private String reason;

    @NotBlank(message = "申请人不能为空")
    private String applicant;
}
