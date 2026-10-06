package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.LoanApplyRequest;
import com.evops.dto.LoanApproveRequest;
import com.evops.dto.LoanExtendRequest;
import com.evops.entity.LoanApplication;
import com.evops.entity.LoanApprovalStep;
import com.evops.service.LoanApprovalService;
import com.evops.service.LoanCompensationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物借阅审批（museum-10）：
 * 申请 -> 按保护等级配置的审批链逐级审批（高风险追加馆长签名）
 * -> 通过生成借阅快照并贯通借出；归还延期须重新审批；
 * 保险资料按馆区脱敏；修复部分成功补偿记录成对可查。
 */
@RestController
@RequestMapping("/api")
public class LoanApprovalController {

    private final LoanApprovalService loanApprovalService;
    private final LoanCompensationService loanCompensationService;

    public LoanApprovalController(LoanApprovalService loanApprovalService,
                                  LoanCompensationService loanCompensationService) {
        this.loanApprovalService = loanApprovalService;
        this.loanCompensationService = loanCompensationService;
    }

    /** 借阅申请：场景/时长按等级校验，展陈计划与修复状态拦截 */
    @PostMapping("/loan-applications")
    public ApiResponse<LoanApplication> apply(@Valid @RequestBody LoanApplyRequest request) {
        return ApiResponse.ok(loanApprovalService.apply(request));
    }

    /** 申请列表：保险资料按查看者馆区脱敏 */
    @GetMapping("/loan-applications")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "hallId", required = false) Long hallId) {
        return ApiResponse.ok(loanApprovalService.list(relicId, status, hallId));
    }

    /** 申请详情：申请单 + 审批记录 + 借阅快照 + 贯通借出单 + 延期子单 */
    @GetMapping("/loan-applications/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable Long id,
            @RequestParam(value = "hallId", required = false) Long hallId) {
        return ApiResponse.ok(loanApprovalService.detail(id, hallId));
    }

    /** 审批签署：按审批链顺序，REJECT 封存只读，末步通过生成快照并贯通借出 */
    @PostMapping("/loan-applications/{id}/approve")
    public ApiResponse<LoanApprovalStep> approve(@PathVariable Long id,
                                                 @Valid @RequestBody LoanApproveRequest request) {
        return ApiResponse.ok(loanApprovalService.approve(id, request));
    }

    /** 归还延期：生成 EXTENSION 子单，重新走完整审批链 */
    @PostMapping("/loan-applications/{id}/extensions")
    public ApiResponse<LoanApplication> extend(@PathVariable Long id,
                                               @Valid @RequestBody LoanExtendRequest request) {
        return ApiResponse.ok(loanApprovalService.extend(id, request));
    }

    /** 补偿记录：修复部分成功时保护等级与借阅申请的补偿彼此对应 */
    @GetMapping("/loan-compensations")
    public ApiResponse<List<Map<String, Object>>> compensations(
            @RequestParam(value = "relicId", required = false) Long relicId) {
        return ApiResponse.ok(loanCompensationService.listGrouped(relicId));
    }
}
