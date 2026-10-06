package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class LoanApplyRequest {

    @NotBlank(message = "申请单号不能为空")
    private String applyNo;

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    @NotNull(message = "受理馆区不能为空")
    private Long hallId;

    @NotBlank(message = "借阅场景不能为空")
    private String scenario;

    @NotNull(message = "借阅天数不能为空")
    @Min(value = 1, message = "借阅天数至少 1 天")
    private Integer borrowDays;

    @NotBlank(message = "申请人不能为空")
    private String applicant;

    private String purpose;

    @NotNull(message = "保险金额不能为空")
    @DecimalMin(value = "0", message = "保险金额不能为负")
    private BigDecimal insuranceAmount;

    /** 承保公司 */
    private String insurer;

    /** 保单号 */
    private String policyNo;
}
