package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文物借阅审批配置（museum.loan-approval.*）。
 * 按保存等级（ONE/TWO/THREE）配置可借阅场景、最高时长（天）与审批角色链；
 * 高风险等级的审批链尾自动追加馆长 DIRECTOR 签名；
 * 修复部分成功（CONDITIONAL 验收）后的保护等级补偿目标也按等级配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.loan-approval")
public class MuseumLoanApprovalProperties {

    /** 各等级可借阅场景：EXHIBITION 展览 / RESEARCH 研究 / PHOTOGRAPHY 拍摄 */
    private Map<String, List<String>> scenarios = new HashMap<>();

    /** 各等级最高借阅时长（天） */
    private Map<String, Integer> maxDays = new HashMap<>();

    /** 各等级审批角色链（按序审批） */
    private Map<String, List<String>> approvalRoles = new HashMap<>();

    /** 高风险等级：审批链尾自动追加馆长 DIRECTOR 签名 */
    private List<String> highRiskGrades = new ArrayList<>();

    /** 修复部分成功（CONDITIONAL 验收）后的保护等级补偿目标 */
    private Map<String, String> conditionalGradeAdjust = new HashMap<>();

    {
        scenarios.put("ONE", Arrays.asList("RESEARCH"));
        scenarios.put("TWO", Arrays.asList("EXHIBITION", "RESEARCH"));
        scenarios.put("THREE", Arrays.asList("EXHIBITION", "RESEARCH", "PHOTOGRAPHY"));

        maxDays.put("ONE", 30);
        maxDays.put("TWO", 90);
        maxDays.put("THREE", 180);

        approvalRoles.put("ONE", Arrays.asList("CURATOR"));
        approvalRoles.put("TWO", Arrays.asList("CURATOR"));
        approvalRoles.put("THREE", Arrays.asList("CURATOR"));

        highRiskGrades.add("ONE");

        conditionalGradeAdjust.put("ONE", "ONE");
        conditionalGradeAdjust.put("TWO", "ONE");
        conditionalGradeAdjust.put("THREE", "TWO");
    }

    private static final List<String> DEFAULT_SCENARIOS = Arrays.asList("RESEARCH");
    private static final int DEFAULT_MAX_DAYS = 30;
    private static final List<String> DEFAULT_ROLES = Arrays.asList("CURATOR");

    /** 某等级可借阅场景；未配置时按最严默认（仅研究） */
    public List<String> scenariosFor(String grade) {
        List<String> list = scenarios.get(grade);
        return (list == null || list.isEmpty()) ? DEFAULT_SCENARIOS : list;
    }

    /** 某等级最高借阅时长（天）；未配置时按 30 天兜底 */
    public int maxDaysFor(String grade) {
        Integer days = maxDays.get(grade);
        return days == null ? DEFAULT_MAX_DAYS : days;
    }

    /**
     * 某等级完整审批链：配置角色链 + 高风险等级链尾追加馆长 DIRECTOR。
     * 链中已含 DIRECTOR 时不重复追加。
     */
    public List<String> approvalChainFor(String grade) {
        List<String> roles = approvalRoles.get(grade);
        List<String> chain = new ArrayList<>(
                (roles == null || roles.isEmpty()) ? DEFAULT_ROLES : roles);
        if (highRiskGrades.contains(grade) && !chain.contains("DIRECTOR")) {
            chain.add("DIRECTOR");
        }
        return chain;
    }

    /** 修复部分成功后的保护等级补偿目标；未配置时维持原级 */
    public String adjustGradeFor(String grade) {
        String target = conditionalGradeAdjust.get(grade);
        return (target == null || target.isEmpty()) ? grade : target;
    }
}
