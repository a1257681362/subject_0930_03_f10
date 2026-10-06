package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumLoanApprovalProperties;
import com.evops.entity.LoanApplication;
import com.evops.entity.LoanCompensation;
import com.evops.entity.Relic;
import com.evops.entity.RepairProject;
import com.evops.mapper.LoanApplicationMapper;
import com.evops.mapper.LoanCompensationMapper;
import com.evops.mapper.RelicMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复部分成功（CONDITIONAL 验收）补偿：
 * 保护等级补偿（GRADE）与借阅申请补偿（LOAN）成对登记、彼此对应——
 * 同一次补偿事件共享 compensate_no（"CP-" + 项目号），
 * 每条 LOAN 行 pair_id 指向本次的 GRADE 行；
 * 等级调整同步更新文物档案，审批中申请的借阅天数按新等级上限压缩。
 */
@Service
public class LoanCompensationService {

    private final LoanCompensationMapper compensationMapper;
    private final LoanApplicationMapper applicationMapper;
    private final RelicMapper relicMapper;
    private final LedgerService ledgerService;
    private final MuseumLoanApprovalProperties approvalProperties;

    public LoanCompensationService(LoanCompensationMapper compensationMapper,
                                   LoanApplicationMapper applicationMapper,
                                   RelicMapper relicMapper,
                                   LedgerService ledgerService,
                                   MuseumLoanApprovalProperties approvalProperties) {
        this.compensationMapper = compensationMapper;
        this.applicationMapper = applicationMapper;
        this.relicMapper = relicMapper;
        this.ledgerService = ledgerService;
        this.approvalProperties = approvalProperties;
    }

    /**
     * CONDITIONAL 验收触发的补偿（与验收同事务）：
     * 1) 按配置调整保护等级并写 GRADE 侧补偿；
     * 2) 该文物每条审批中申请按新等级上限压缩天数，写 LOAN 侧补偿并互指。
     */
    @Transactional
    public List<LoanCompensation> compensateOnConditionalAccept(RepairProject project) {
        Relic relic = relicMapper.selectById(project.getRelicId());
        String fromGrade = relic.getPreservationGrade();
        String toGrade = approvalProperties.adjustGradeFor(fromGrade);
        String compensateNo = "CP-" + project.getProjectNo();
        LocalDateTime now = LocalDateTime.now();
        List<LoanCompensation> created = new ArrayList<>();

        // ---- 保护等级补偿（GRADE 侧）----
        if (!fromGrade.equals(toGrade)) {
            relic.setPreservationGrade(toGrade);
            relicMapper.updateById(relic);
        }
        LoanCompensation gradeRow = new LoanCompensation();
        gradeRow.setCompensateNo(compensateNo);
        gradeRow.setSide(COMP_SIDE_GRADE);
        gradeRow.setRelicId(relic.getId());
        gradeRow.setProjectId(project.getId());
        gradeRow.setFromGrade(fromGrade);
        gradeRow.setToGrade(toGrade);
        gradeRow.setAction(fromGrade.equals(toGrade) ? COMP_ACTION_NO_CHANGE : COMP_ACTION_ADJUST_GRADE);
        gradeRow.setDetail("修复部分成功（CONDITIONAL 验收），保护等级补偿："
                + fromGrade + " -> " + toGrade);
        compensationMapper.insert(gradeRow);
        created.add(gradeRow);

        // ---- 借阅申请补偿（LOAN 侧，与 GRADE 行彼此对应）----
        int maxDays = approvalProperties.maxDaysFor(toGrade);
        List<LoanApplication> affected = applicationMapper.selectList(
                new LambdaQueryWrapper<LoanApplication>()
                        .eq(LoanApplication::getRelicId, relic.getId())
                        .eq(LoanApplication::getStatus, LAP_APPROVING)
                        .orderByAsc(LoanApplication::getId));
        for (LoanApplication app : affected) {
            int fromDays = app.getBorrowDays();
            int toDays = Math.min(fromDays, maxDays);
            LoanCompensation loanRow = new LoanCompensation();
            loanRow.setCompensateNo(compensateNo);
            loanRow.setSide(COMP_SIDE_LOAN);
            loanRow.setPairId(gradeRow.getId());
            loanRow.setRelicId(relic.getId());
            loanRow.setProjectId(project.getId());
            loanRow.setApplicationId(app.getId());
            loanRow.setFromGrade(fromGrade);
            loanRow.setToGrade(toGrade);
            loanRow.setFromDays(fromDays);
            loanRow.setToDays(toDays);
            loanRow.setAction(toDays < fromDays ? COMP_ACTION_CLAMP_DAYS : COMP_ACTION_NO_CHANGE);
            loanRow.setDetail("申请单 " + app.getApplyNo() + " 按新保护等级（" + toGrade
                    + "）最高时长 " + maxDays + " 天补偿：借阅天数 " + fromDays + " -> " + toDays);
            compensationMapper.insert(loanRow);
            created.add(loanRow);

            if (toDays < fromDays) {
                app.setBorrowDays(toDays);
                applicationMapper.updateById(app);
            }
        }

        ledgerService.append(relic.getId(), project.getId(), null,
                ACTION_LOAN_COMPENSATE, compensateNo,
                "修复部分成功补偿登记：保护等级 " + fromGrade + "->" + toGrade
                        + "，受影响审批中申请 " + affected.size() + " 条，补偿记录 "
                        + created.size() + " 条彼此对应", now);
        return created;
    }

    /** 补偿记录成对查询：按 compensate_no 分组，GRADE 行与其 LOAN 行彼此对应 */
    public List<Map<String, Object>> listGrouped(Long relicId) {
        LambdaQueryWrapper<LoanCompensation> wrapper = new LambdaQueryWrapper<LoanCompensation>()
                .orderByAsc(LoanCompensation::getId);
        if (relicId != null) {
            wrapper.eq(LoanCompensation::getRelicId, relicId);
        }
        List<LoanCompensation> rows = compensationMapper.selectList(wrapper);

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (LoanCompensation row : rows) {
            Map<String, Object> group = groups.computeIfAbsent(row.getCompensateNo(), no -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("compensateNo", no);
                g.put("grade", null);
                g.put("loans", new ArrayList<LoanCompensation>());
                return g;
            });
            if (COMP_SIDE_GRADE.equals(row.getSide())) {
                group.put("grade", row);
            } else {
                @SuppressWarnings("unchecked")
                List<LoanCompensation> loans = (List<LoanCompensation>) group.get("loans");
                loans.add(row);
            }
        }
        return new ArrayList<>(groups.values());
    }
}
