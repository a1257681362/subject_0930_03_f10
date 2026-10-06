package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumLoanApprovalProperties;
import com.evops.dto.LoanApplyRequest;
import com.evops.dto.LoanApproveRequest;
import com.evops.dto.LoanExtendRequest;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.entity.LoanAgreement;
import com.evops.entity.LoanApplication;
import com.evops.entity.LoanApprovalStep;
import com.evops.entity.LoanSnapshot;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairProject;
import com.evops.mapper.HallMapper;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.LoanApplicationMapper;
import com.evops.mapper.LoanApprovalStepMapper;
import com.evops.mapper.LoanSnapshotMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物借阅审批（museum-10）：
 *   申请（按保护等级校验场景/时长/展陈计划/修复状态/占用）
 *   -> 按配置审批链逐级审批（高风险等级链尾追加馆长 DIRECTOR）
 *   -> 通过后生成借阅快照并写 t_relic_loan(LENT) 贯通既有占用校验。
 * 归还延期须以 EXTENSION 子单重新走完整审批链。
 * 保险资料按馆区脱敏：审批人不能查看不属于自己馆区的完整保险资料。
 */
@Service
public class LoanApprovalService {

    private final LoanApplicationMapper applicationMapper;
    private final LoanApprovalStepMapper stepMapper;
    private final LoanSnapshotMapper snapshotMapper;
    private final RelicLoanMapper loanMapper;
    private final LoanAgreementMapper agreementMapper;
    private final HallRelicMapper hallRelicMapper;
    private final RepairProjectMapper projectMapper;
    private final HallMapper hallMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final MuseumLoanApprovalProperties approvalProperties;

    public LoanApprovalService(LoanApplicationMapper applicationMapper,
                               LoanApprovalStepMapper stepMapper,
                               LoanSnapshotMapper snapshotMapper,
                               RelicLoanMapper loanMapper,
                               LoanAgreementMapper agreementMapper,
                               HallRelicMapper hallRelicMapper,
                               RepairProjectMapper projectMapper,
                               HallMapper hallMapper,
                               RelicService relicService,
                               LedgerService ledgerService,
                               MuseumLoanApprovalProperties approvalProperties) {
        this.applicationMapper = applicationMapper;
        this.stepMapper = stepMapper;
        this.snapshotMapper = snapshotMapper;
        this.loanMapper = loanMapper;
        this.agreementMapper = agreementMapper;
        this.hallRelicMapper = hallRelicMapper;
        this.projectMapper = projectMapper;
        this.hallMapper = hallMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.approvalProperties = approvalProperties;
    }

    // ------------------------------------------------------------------
    // 借阅申请
    // ------------------------------------------------------------------

    /**
     * 借阅申请：场景/时长按保护等级配置校验；展陈计划与修复状态拦截；
     * 既有占用（借出/协议/审批中申请）同一口径。落库 APPROVING。
     */
    @Transactional
    public LoanApplication apply(LoanApplyRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        requireHall(req.getHallId());

        if (applicationMapper.selectCount(new LambdaQueryWrapper<LoanApplication>()
                .eq(LoanApplication::getApplyNo, req.getApplyNo())) > 0) {
            throw new IllegalArgumentException("申请单号已存在: " + req.getApplyNo());
        }
        String grade = relic.getPreservationGrade();
        if (!approvalProperties.scenariosFor(grade).contains(req.getScenario())) {
            throw new IllegalArgumentException("借阅场景 " + req.getScenario()
                    + " 不在该保护等级（" + grade + "）可借阅范围内，允许场景："
                    + String.join("/", approvalProperties.scenariosFor(grade)));
        }
        int maxDays = approvalProperties.maxDaysFor(grade);
        if (req.getBorrowDays() > maxDays) {
            throw new IllegalArgumentException("借阅天数超过该保护等级（" + grade + "）最高时长："
                    + req.getBorrowDays() + " 天 > " + maxDays + " 天");
        }
        requireRelicBorrowable(relic, null);

        LoanApplication app = new LoanApplication();
        app.setApplyNo(req.getApplyNo());
        app.setRelicId(relic.getId());
        app.setHallId(req.getHallId());
        app.setAppType(LAP_TYPE_INITIAL);
        app.setGrade(grade);
        app.setScenario(req.getScenario());
        app.setBorrowDays(req.getBorrowDays());
        app.setApplicant(req.getApplicant());
        app.setPurpose(req.getPurpose());
        app.setInsuranceAmount(req.getInsuranceAmount());
        app.setInsurer(req.getInsurer());
        app.setPolicyNo(req.getPolicyNo());
        app.setStatus(LAP_APPROVING);
        applicationMapper.insert(app);

        ledgerService.append(relic.getId(), null, null,
                ACTION_LOAN_APPLY, app.getApplyNo(),
                "借阅申请登记（" + req.getScenario() + "，" + req.getBorrowDays() + " 天），申请人："
                        + req.getApplicant() + "，审批链："
                        + String.join("->", approvalProperties.approvalChainFor(grade)),
                LocalDateTime.now());
        return app;
    }

    // ------------------------------------------------------------------
    // 逐级审批
    // ------------------------------------------------------------------

    /**
     * 审批签署：必须按审批链顺序；REJECT 即封存只读；
     * 末步 APPROVE 重跑展陈/修复/占用校验，通过后生成借阅快照并贯通借出。
     */
    @Transactional
    public LoanApprovalStep approve(Long applicationId, LoanApproveRequest req) {
        LoanApplication app = requireApplication(applicationId);
        if (!LAP_APPROVING.equals(app.getStatus())) {
            throw new IllegalArgumentException("申请单已"
                    + (LAP_REJECTED.equals(app.getStatus()) ? "拒绝" : "通过")
                    + "，保持只读: " + app.getApplyNo());
        }
        if (!LOAN_DECISION_APPROVE.equals(req.getDecision())
                && !LOAN_DECISION_REJECT.equals(req.getDecision())) {
            throw new IllegalArgumentException("审批决定只能为 APPROVE/REJECT");
        }

        List<String> chain = approvalProperties.approvalChainFor(app.getGrade());
        List<LoanApprovalStep> signed = listSteps(applicationId);
        int stepNo = signed.size() + 1;
        String expectedRole = chain.get(signed.size());
        if (!expectedRole.equals(req.getApproverRole())) {
            throw new IllegalArgumentException("未按审批链顺序审批：当前应签角色为" + expectedRole
                    + "（第" + stepNo + "步），实际为" + req.getApproverRole());
        }

        LocalDateTime now = LocalDateTime.now();
        LoanApprovalStep step = new LoanApprovalStep();
        step.setApplicationId(applicationId);
        step.setStepNo(stepNo);
        step.setApproverRole(expectedRole);
        step.setApproverName(req.getApproverName());
        step.setDecision(req.getDecision());
        step.setOpinion(req.getOpinion());
        step.setSignedAt(now);
        stepMapper.insert(step);

        if (LOAN_DECISION_REJECT.equals(req.getDecision())) {
            app.setStatus(LAP_REJECTED);
            app.setClosedAt(now);
            applicationMapper.updateById(app);
            ledgerService.append(app.getRelicId(), null, null,
                    ACTION_LOAN_REJECT, app.getApplyNo(),
                    "借阅申请被" + req.getApproverName() + "（" + expectedRole + "）拒绝，申请单封存只读"
                            + (req.getOpinion() == null ? "" : "，意见：" + req.getOpinion()), now);
            return step;
        }

        if (stepNo == chain.size()) {
            // 末步通过：申请后状态可能已变，重跑占用/展陈/修复校验
            if (LAP_TYPE_EXTENSION.equals(app.getAppType())) {
                approveExtension(app, now);
            } else {
                approveInitial(app, now);
            }
        } else {
            ledgerService.append(app.getRelicId(), null, null,
                    ACTION_LOAN_APPROVE, app.getApplyNo(),
                    "借阅申请第" + stepNo + "步审批通过：" + req.getApproverName()
                            + "（" + expectedRole + "）", now);
        }
        return step;
    }

    /** 首借单末步通过：重跑校验 -> 贯通借出 -> 生成快照 */
    private void approveInitial(LoanApplication app, LocalDateTime now) {
        Relic relic = relicService.requireActiveRelic(app.getRelicId());
        requireRelicBorrowable(relic, app.getId());

        RelicLoan loan = new RelicLoan();
        loan.setLoanNo(app.getApplyNo());
        loan.setRelicId(app.getRelicId());
        loan.setBorrower(app.getApplicant());
        loan.setPurpose(app.getPurpose());
        loan.setLentAt(now);
        loan.setExpectedReturnAt(now.plusDays(app.getBorrowDays()));
        loan.setStatus(LOAN_LENT);
        loanMapper.insert(loan);

        app.setStatus(LAP_APPROVED);
        app.setLoanId(loan.getId());
        app.setExpectedReturnAt(loan.getExpectedReturnAt());
        app.setClosedAt(now);
        applicationMapper.updateById(app);

        LoanSnapshot snapshot = createSnapshot(app, relic, loan.getId(),
                app.getBorrowDays(), loan.getExpectedReturnAt());
        ledgerService.append(app.getRelicId(), null, loan.getId(),
                ACTION_LOAN_APPROVE, app.getApplyNo(),
                "借阅申请审批链全部通过，借出 " + app.getBorrowDays() + " 天", now);
        ledgerService.append(app.getRelicId(), null, loan.getId(),
                ACTION_LOAN_SNAPSHOT, snapshot.getSnapshotNo(),
                "借阅快照生成：" + snapshot.getSnapshotNo() + "，审批链：" + snapshot.getApprovalChain(), now);
    }

    /** 延期单末步通过：首借单借出记录应还日期顺延，生成延期快照 */
    private void approveExtension(LoanApplication app, LocalDateTime now) {
        LoanApplication parent = requireApplication(app.getParentId());
        Relic relic = relicService.requireActiveRelic(app.getRelicId());
        RelicLoan loan = requireLentLoan(parent);

        loan.setExpectedReturnAt(loan.getExpectedReturnAt().plusDays(app.getBorrowDays()));
        loanMapper.updateById(loan);

        app.setStatus(LAP_APPROVED);
        app.setLoanId(loan.getId());
        app.setExpectedReturnAt(loan.getExpectedReturnAt());
        app.setClosedAt(now);
        applicationMapper.updateById(app);

        LoanSnapshot snapshot = createSnapshot(app, relic, loan.getId(),
                app.getBorrowDays(), loan.getExpectedReturnAt());
        ledgerService.append(app.getRelicId(), null, loan.getId(),
                ACTION_LOAN_EXTEND, app.getApplyNo(),
                "归还延期审批通过（首借单 " + parent.getApplyNo() + "），顺延 " + app.getBorrowDays()
                        + " 天，新应还时间：" + loan.getExpectedReturnAt(), now);
        ledgerService.append(app.getRelicId(), null, loan.getId(),
                ACTION_LOAN_SNAPSHOT, snapshot.getSnapshotNo(),
                "延期借阅快照生成：" + snapshot.getSnapshotNo(), now);
    }

    // ------------------------------------------------------------------
    // 归还延期：重新审批
    // ------------------------------------------------------------------

    /**
     * 延期申请：首借单须 APPROVED 且借出未还；延期天数不超等级最高时长；
     * 生成 EXTENSION 子单重新走完整审批链。
     */
    @Transactional
    public LoanApplication extend(Long parentId, LoanExtendRequest req) {
        LoanApplication parent = requireApplication(parentId);
        if (!LAP_APPROVED.equals(parent.getStatus())) {
            throw new IllegalArgumentException("首借单未批准，不能申请延期: " + parent.getApplyNo());
        }
        if (!LAP_TYPE_INITIAL.equals(parent.getAppType())) {
            throw new IllegalArgumentException("延期单不能再次延期，请对首借单申请: " + parent.getApplyNo());
        }
        requireLentLoan(parent);

        Relic relic = relicService.requireActiveRelic(parent.getRelicId());
        if (applicationMapper.selectCount(new LambdaQueryWrapper<LoanApplication>()
                .eq(LoanApplication::getApplyNo, req.getApplyNo())) > 0) {
            throw new IllegalArgumentException("申请单号已存在: " + req.getApplyNo());
        }
        Long approving = applicationMapper.selectCount(new LambdaQueryWrapper<LoanApplication>()
                .eq(LoanApplication::getRelicId, parent.getRelicId())
                .eq(LoanApplication::getStatus, LAP_APPROVING));
        if (approving != null && approving > 0) {
            throw new IllegalArgumentException("文物存在审批中的借阅申请，不能重复申请");
        }
        String grade = relic.getPreservationGrade();
        int maxDays = approvalProperties.maxDaysFor(grade);
        if (req.getExtendDays() > maxDays) {
            throw new IllegalArgumentException("延期天数超过该保护等级（" + grade + "）最高时长："
                    + req.getExtendDays() + " 天 > " + maxDays + " 天");
        }

        LoanApplication app = new LoanApplication();
        app.setApplyNo(req.getApplyNo());
        app.setRelicId(parent.getRelicId());
        app.setHallId(parent.getHallId());
        app.setAppType(LAP_TYPE_EXTENSION);
        app.setParentId(parent.getId());
        app.setGrade(grade);
        app.setScenario(parent.getScenario());
        app.setBorrowDays(req.getExtendDays());
        app.setApplicant(req.getApplicant());
        app.setPurpose(req.getReason());
        app.setInsuranceAmount(parent.getInsuranceAmount());
        app.setInsurer(parent.getInsurer());
        app.setPolicyNo(parent.getPolicyNo());
        app.setStatus(LAP_APPROVING);
        applicationMapper.insert(app);

        ledgerService.append(parent.getRelicId(), null, parent.getLoanId(),
                ACTION_LOAN_APPLY, app.getApplyNo(),
                "归还延期申请登记（首借单 " + parent.getApplyNo() + "，延期 " + req.getExtendDays()
                        + " 天），须重新审批；原因：" + req.getReason(), LocalDateTime.now());
        return app;
    }

    // ------------------------------------------------------------------
    // 查询（保险资料按馆区脱敏）
    // ------------------------------------------------------------------

    public LoanApplication requireApplication(Long id) {
        LoanApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("借阅申请单不存在: id=" + id);
        }
        return app;
    }

    public List<LoanApprovalStep> listSteps(Long applicationId) {
        return stepMapper.selectList(new LambdaQueryWrapper<LoanApprovalStep>()
                .eq(LoanApprovalStep::getApplicationId, applicationId)
                .orderByAsc(LoanApprovalStep::getStepNo));
    }

    /** 申请列表：保险资料按查看者馆区脱敏 */
    public List<Map<String, Object>> list(Long relicId, String status, Long viewerHallId) {
        LambdaQueryWrapper<LoanApplication> wrapper = new LambdaQueryWrapper<LoanApplication>()
                .orderByDesc(LoanApplication::getId);
        if (relicId != null) {
            wrapper.eq(LoanApplication::getRelicId, relicId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(LoanApplication::getStatus, status);
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (LoanApplication app : applicationMapper.selectList(wrapper)) {
            views.add(applicationView(app, viewerHallId));
        }
        return views;
    }

    /** 申请详情：申请单 + 审批记录 + 快照（含延期子单快照）+ 贯通借出单 + 延期子单；保险按馆区脱敏 */
    public Map<String, Object> detail(Long applicationId, Long viewerHallId) {
        LoanApplication app = requireApplication(applicationId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("application", applicationView(app, viewerHallId));
        detail.put("approvalChain", approvalProperties.approvalChainFor(app.getGrade()));
        detail.put("steps", listSteps(applicationId));

        List<LoanApplication> children = applicationMapper.selectList(
                new LambdaQueryWrapper<LoanApplication>()
                        .eq(LoanApplication::getParentId, applicationId)
                        .orderByAsc(LoanApplication::getId));
        List<Long> snapshotAppIds = new ArrayList<>();
        snapshotAppIds.add(applicationId);
        for (LoanApplication child : children) {
            snapshotAppIds.add(child.getId());
        }
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (LoanSnapshot snapshot : snapshotMapper.selectList(new LambdaQueryWrapper<LoanSnapshot>()
                .in(LoanSnapshot::getApplicationId, snapshotAppIds)
                .orderByAsc(LoanSnapshot::getId))) {
            snapshots.add(snapshotView(snapshot, viewerHallId));
        }
        detail.put("snapshots", snapshots);

        detail.put("loan", app.getLoanId() == null ? null : loanMapper.selectById(app.getLoanId()));

        List<Map<String, Object>> extensions = new ArrayList<>();
        for (LoanApplication child : children) {
            extensions.add(applicationView(child, viewerHallId));
        }
        detail.put("extensions", extensions);
        return detail;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 展陈计划 / 修复状态 / 既有占用 三项拦截（申请与末步通过同一口径；末步通过时排除自身） */
    private void requireRelicBorrowable(Relic relic, Long excludeApplicationId) {
        Long onDisplay = hallRelicMapper.selectCount(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getRelicId, relic.getId())
                .eq(HallRelic::getStatus, DISPLAY_ON));
        if (onDisplay != null && onDisplay > 0) {
            throw new IllegalArgumentException("文物正在展陈中，不能申请借阅: " + relic.getRelicNo());
        }
        Long openProjects = projectMapper.selectCount(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getRelicId, relic.getId())
                .in(RepairProject::getStatus, PROJECT_PLANNED, PROJECT_REPAIRING, PROJECT_REPAIRED));
        if (openProjects != null && openProjects > 0) {
            throw new IllegalArgumentException("文物存在未闭环修复项目，不能申请借阅: " + relic.getRelicNo());
        }
        Long openLoans = loanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, relic.getId())
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openLoans != null && openLoans > 0) {
            throw new IllegalArgumentException("文物仍有借出未归还，不能申请借阅: " + relic.getRelicNo());
        }
        Long openAgreements = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, relic.getId())
                .notIn(LoanAgreement::getStatus, AGR_RETURNED, AGR_CANCELLED));
        if (openAgreements != null && openAgreements > 0) {
            throw new IllegalArgumentException("文物存在未闭环借展协议，不能申请借阅: " + relic.getRelicNo());
        }
        Long approving = applicationMapper.selectCount(new LambdaQueryWrapper<LoanApplication>()
                .eq(LoanApplication::getRelicId, relic.getId())
                .eq(LoanApplication::getStatus, LAP_APPROVING)
                .ne(excludeApplicationId != null, LoanApplication::getId, excludeApplicationId));
        if (approving != null && approving > 0) {
            throw new IllegalArgumentException("文物存在审批中的借阅申请，不能重复申请: " + relic.getRelicNo());
        }
    }

    private RelicLoan requireLentLoan(LoanApplication parent) {
        RelicLoan loan = parent.getLoanId() == null ? null : loanMapper.selectById(parent.getLoanId());
        if (loan == null || !LOAN_LENT.equals(loan.getStatus())) {
            throw new IllegalArgumentException("借出单已归还，不能申请延期: " + parent.getApplyNo());
        }
        return loan;
    }

    private LoanSnapshot createSnapshot(LoanApplication app, Relic relic, Long loanId,
                                        int approvedDays, LocalDateTime expectedReturnAt) {
        List<LoanApprovalStep> steps = listSteps(app.getId());
        StringBuilder chain = new StringBuilder();
        for (LoanApprovalStep step : steps) {
            if (chain.length() > 0) {
                chain.append("->");
            }
            chain.append(step.getApproverRole()).append(":").append(step.getApproverName());
        }
        LoanSnapshot snapshot = new LoanSnapshot();
        snapshot.setSnapshotNo(app.getApplyNo() + "-SNAP");
        snapshot.setApplicationId(app.getId());
        snapshot.setLoanId(loanId);
        snapshot.setRelicId(relic.getId());
        snapshot.setRelicNo(relic.getRelicNo());
        snapshot.setRelicName(relic.getName());
        snapshot.setGrade(app.getGrade());
        snapshot.setMaterial(relic.getMaterial());
        snapshot.setScenario(app.getScenario());
        snapshot.setApprovedDays(approvedDays);
        snapshot.setApplicant(app.getApplicant());
        snapshot.setInsuranceAmount(app.getInsuranceAmount());
        snapshot.setInsurer(app.getInsurer());
        snapshot.setPolicyNo(app.getPolicyNo());
        snapshot.setHallId(app.getHallId());
        snapshot.setApprovalChain(chain.toString());
        snapshot.setExpectedReturnAt(expectedReturnAt);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /** 保险资料脱敏：仅本馆区可见完整保险资料 */
    private Map<String, Object> applicationView(LoanApplication app, Long viewerHallId) {
        boolean ownHall = viewerHallId != null && viewerHallId.equals(app.getHallId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", app.getId());
        view.put("applyNo", app.getApplyNo());
        view.put("relicId", app.getRelicId());
        view.put("hallId", app.getHallId());
        view.put("appType", app.getAppType());
        view.put("parentId", app.getParentId());
        view.put("grade", app.getGrade());
        view.put("scenario", app.getScenario());
        view.put("borrowDays", app.getBorrowDays());
        view.put("applicant", app.getApplicant());
        view.put("purpose", app.getPurpose());
        view.put("status", app.getStatus());
        view.put("loanId", app.getLoanId());
        view.put("expectedReturnAt", app.getExpectedReturnAt());
        view.put("closedAt", app.getClosedAt());
        if (ownHall) {
            view.put("insuranceAmount", app.getInsuranceAmount());
            view.put("insurer", app.getInsurer());
            view.put("policyNo", app.getPolicyNo());
            view.put("insuranceMasked", false);
        } else {
            view.put("insuranceAmount", null);
            view.put("insurer", "***");
            view.put("policyNo", null);
            view.put("insuranceMasked", true);
        }
        return view;
    }

    private Map<String, Object> snapshotView(LoanSnapshot snapshot, Long viewerHallId) {
        boolean ownHall = viewerHallId != null && viewerHallId.equals(snapshot.getHallId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", snapshot.getId());
        view.put("snapshotNo", snapshot.getSnapshotNo());
        view.put("applicationId", snapshot.getApplicationId());
        view.put("loanId", snapshot.getLoanId());
        view.put("relicId", snapshot.getRelicId());
        view.put("relicNo", snapshot.getRelicNo());
        view.put("relicName", snapshot.getRelicName());
        view.put("grade", snapshot.getGrade());
        view.put("material", snapshot.getMaterial());
        view.put("scenario", snapshot.getScenario());
        view.put("approvedDays", snapshot.getApprovedDays());
        view.put("applicant", snapshot.getApplicant());
        view.put("hallId", snapshot.getHallId());
        view.put("approvalChain", snapshot.getApprovalChain());
        view.put("expectedReturnAt", snapshot.getExpectedReturnAt());
        if (ownHall) {
            view.put("insuranceAmount", snapshot.getInsuranceAmount());
            view.put("insurer", snapshot.getInsurer());
            view.put("policyNo", snapshot.getPolicyNo());
            view.put("insuranceMasked", false);
        } else {
            view.put("insuranceAmount", null);
            view.put("insurer", "***");
            view.put("policyNo", null);
            view.put("insuranceMasked", true);
        }
        return view;
    }

    private void requireHall(Long hallId) {
        Hall hall = hallMapper.selectById(hallId);
        if (hall == null) {
            throw new IllegalArgumentException("展厅不存在: id=" + hallId);
        }
    }
}
