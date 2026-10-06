package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物借阅审批（museum-10）业务规则验收：
 * 按保护等级配置场景/时长/审批角色；申请校验展陈计划与修复状态；
 * 高风险文物追加馆长签名；通过后生成借阅快照并贯通借出；
 * 归还延期重新审批；保险资料按馆区脱敏；
 * 修复部分成功时保护等级与借阅申请的补偿记录彼此对应。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-loan-approval;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanApprovalTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loan_approval_rules_hold() throws Exception {
        // ---- 建档：一级/三级文物 + 两个馆区 ----
        long relicOne = createRelic("{"
                + "\"relicNo\":\"LAP-R1\",\"name\":\"借阅审批一级鼎\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"商代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"ONE\"}");
        long relicTwo = createRelic("{"
                + "\"relicNo\":\"LAP-R2\",\"name\":\"借阅审批三级瓷\",\"category\":\"瓷器\","
                + "\"material\":\"瓷\",\"era\":\"清代\",\"source\":\"征集\","
                + "\"preservationGrade\":\"THREE\"}");
        long relicThree = createRelic("{"
                + "\"relicNo\":\"LAP-R3\",\"name\":\"补偿测试三级画\",\"category\":\"书画\","
                + "\"material\":\"纸本\",\"era\":\"明代\",\"source\":\"捐赠\","
                + "\"preservationGrade\":\"THREE\"}");
        long hallA = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"H-LAP-A\",\"name\":\"一展厅\",\"custodian\":\"保管员甲\"}"))
                .get("id").asLong();
        long hallB = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"H-LAP-B\",\"name\":\"二展厅\",\"custodian\":\"保管员乙\"}"))
                .get("id").asLong();

        // ---- 展陈计划校验：在展文物不能申请借阅 ----
        expectOk(postJson("/api/halls/" + hallA + "/relics",
                "{\"relicId\":" + relicOne + ",\"pointCode\":\"P-01\",\"displayNo\":\"D-01\"}"));
        expectFail(applyJson("LAP-000", relicOne, hallA, "RESEARCH", 10, "申请人甲"),
                "展陈中");
        expectOk(postJson("/api/halls/" + hallA + "/relics/" + relicOne + "/withdraw", "{}"));

        // ---- 修复状态校验：未闭环修复项目不能申请借阅 ----
        long projectOne = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-LAP-1\",\"relicId\":" + relicOne
                + ",\"client\":\"藏品部\",\"plan\":\"前期评估\"}")).get("id").asLong();
        expectFail(applyJson("LAP-000", relicOne, hallA, "RESEARCH", 10, "申请人甲"),
                "未闭环修复项目");
        expectOk(postJson("/api/repair-projects/" + projectOne + "/cancel",
                "{\"reason\":\"评估取消\"}"));

        // ---- 场景与最高时长按保护等级校验 ----
        expectFail(applyJson("LAP-000", relicOne, hallA, "EXHIBITION", 10, "申请人甲"),
                "可借阅范围");
        expectFail(applyJson("LAP-000", relicOne, hallA, "RESEARCH", 31, "申请人甲"),
                "最高时长");

        // ---- 一级文物申请成功：审批链 CURATOR -> DIRECTOR ----
        JsonNode app1 = dataOf(applyJson("LAP-2026-0001", relicOne, hallA, "RESEARCH", 30,
                "省考古所"));
        long app1Id = app1.get("id").asLong();
        assertEquals("APPROVING", app1.get("status").asText());
        assertEquals("ONE", app1.get("grade").asText());
        JsonNode app1Detail = dataOf(getJson("/api/loan-applications/" + app1Id + "?hallId=" + hallA));
        assertEquals(2, app1Detail.get("approvalChain").size());
        assertEquals("CURATOR", app1Detail.get("approvalChain").get(0).asText());
        assertEquals("DIRECTOR", app1Detail.get("approvalChain").get(1).asText(),
                "高风险一级文物必须追加馆长签名");

        // ---- 审批中申请的占用口径：重复申请/旧通道借出/协议登记/撤销均拦截 ----
        expectFail(applyJson("LAP-2026-0002", relicOne, hallA, "RESEARCH", 10, "申请人乙"),
                "审批中的借阅申请");
        expectFail(postJson("/api/loans", "{"
                + "\"loanNo\":\"LO-LAP-OLD\",\"relicId\":" + relicOne
                + ",\"borrower\":\"旧通道单位\"}"), "审批中的借阅申请");
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-LAP-OLD\",\"relicId\":" + relicOne
                + ",\"borrower\":\"协议单位\",\"insuranceAmount\":1000,"
                + "\"transportRoute\":\"路线\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "审批中的借阅申请");
        expectFail(postJson("/api/relics/" + relicOne + "/revoke",
                "{\"reason\":\"测试撤销\"}"), "审批中的借阅申请");

        // ---- 审批链顺序：馆长不能越序先签 ----
        expectFail(approveJson(app1Id, "DIRECTOR", "李馆长", "APPROVE"), "审批链顺序");
        expectOk(approveJson(app1Id, "CURATOR", "王保管", "APPROVE"));
        expectFail(approveJson(app1Id, "CURATOR", "王保管", "APPROVE"), "DIRECTOR");
        expectOk(approveJson(app1Id, "DIRECTOR", "李馆长", "APPROVE"));

        // ---- 通过后：快照生成 + 贯通借出 ----
        JsonNode approved1 = dataOf(getJson("/api/loan-applications/" + app1Id + "?hallId=" + hallA));
        assertEquals("APPROVED", approved1.get("application").get("status").asText());
        JsonNode loan1 = approved1.get("loan");
        assertEquals("LENT", loan1.get("status").asText());
        assertEquals("LAP-2026-0001", loan1.get("loanNo").asText());
        JsonNode snapshots1 = approved1.get("snapshots");
        assertEquals(1, snapshots1.size());
        JsonNode snap1 = snapshots1.get(0);
        assertEquals("LAP-2026-0001-SNAP", snap1.get("snapshotNo").asText());
        assertEquals("ONE", snap1.get("grade").asText());
        assertEquals(30, snap1.get("approvedDays").asInt());
        assertEquals("CURATOR:王保管->DIRECTOR:李馆长", snap1.get("approvalChain").asText());
        assertEquals(500000, snap1.get("insuranceAmount").asInt());
        assertFalse(snap1.get("insuranceMasked").asBoolean(), "本馆区应见完整保险资料");

        // ---- 保险资料按馆区脱敏 ----
        JsonNode otherHall = dataOf(getJson("/api/loan-applications/" + app1Id + "?hallId=" + hallB));
        assertTrue(otherHall.get("application").get("insuranceAmount").isNull(),
                "他馆区审批人不能查看完整保险金额");
        assertEquals("***", otherHall.get("application").get("insurer").asText());
        assertTrue(otherHall.get("application").get("insuranceMasked").asBoolean());
        assertTrue(otherHall.get("snapshots").get(0).get("insuranceMasked").asBoolean(),
                "快照内保险资料同样脱敏");
        JsonNode noHall = dataOf(getJson("/api/loan-applications/" + app1Id));
        assertTrue(noHall.get("application").get("insuranceMasked").asBoolean(),
                "未声明馆区按最低权限脱敏");
        JsonNode listMasked = dataOf(getJson("/api/loan-applications?relicId=" + relicOne));
        assertTrue(listMasked.get(0).get("insuranceMasked").asBoolean());
        JsonNode listOwn = dataOf(getJson("/api/loan-applications?relicId=" + relicOne
                + "&hallId=" + hallA));
        assertFalse(listOwn.get(0).get("insuranceMasked").asBoolean());

        // ---- 贯通借出占用：修复不能开工、文物不能撤销 ----
        long projectLent = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-LAP-2\",\"relicId\":" + relicOne
                + ",\"client\":\"藏品部\",\"plan\":\"借出期间立项\"}")).get("id").asLong();
        expectFail(postEmpty("/api/repair-projects/" + projectLent + "/start"), "尚在外借");
        expectFail(postJson("/api/relics/" + relicOne + "/revoke",
                "{\"reason\":\"测试撤销\"}"), "借出未归还");
        expectOk(postJson("/api/repair-projects/" + projectLent + "/cancel",
                "{\"reason\":\"测试结项\"}"));

        // ---- 归还延期：须重新审批 ----
        String returnAtBefore = loan1.get("expectedReturnAt").asText();
        expectFail(extendJson(app1Id, "LAP-EXT-X", 31, "展期延长", "省考古所"), "最高时长");
        JsonNode ext1 = dataOf(extendJson(app1Id, "LAP-2026-0001-E1", 10, "研究延期", "省考古所"));
        long ext1Id = ext1.get("id").asLong();
        assertEquals("EXTENSION", ext1.get("appType").asText());
        assertEquals(app1Id, ext1.get("parentId").asLong());
        assertEquals("APPROVING", ext1.get("status").asText());
        // 延期同样走完整审批链（一级：CURATOR -> DIRECTOR）
        expectOk(approveJson(ext1Id, "CURATOR", "王保管", "APPROVE"));
        expectOk(approveJson(ext1Id, "DIRECTOR", "李馆长", "APPROVE"));
        JsonNode afterExt = dataOf(getJson("/api/loan-applications/" + app1Id + "?hallId=" + hallA));
        String returnAtAfter = afterExt.get("loan").get("expectedReturnAt").asText();
        assertEquals(LocalDateTime.parse(returnAtBefore).plusDays(10),
                LocalDateTime.parse(returnAtAfter), "延期通过后应还日期顺延 10 天");
        assertEquals(1, afterExt.get("extensions").size());
        assertEquals(2, afterExt.get("snapshots").size(), "首借与延期各生成一条快照");

        // ---- 拒绝封存只读 ----
        JsonNode app2 = dataOf(applyJson("LAP-2026-0003", relicTwo, hallB, "PHOTOGRAPHY", 60,
                "拍摄组"));
        long app2Id = app2.get("id").asLong();
        expectFail(extendJson(app2Id, "LAP-EXT-Y", 5, "未批准先延期", "拍摄组"), "未批准");
        expectOk(approveJson(app2Id, "CURATOR", "赵保管", "REJECT"));
        assertEquals("REJECTED", dataOf(getJson("/api/loan-applications/" + app2Id))
                .get("application").get("status").asText());
        expectFail(approveJson(app2Id, "CURATOR", "赵保管", "APPROVE"), "只读");

        // ---- 修复部分成功：保护等级与借阅申请补偿彼此对应 ----
        JsonNode app3 = dataOf(applyJson("LAP-2026-0004", relicThree, hallA, "RESEARCH", 180,
                "美院研究部"));
        long app3Id = app3.get("id").asLong();
        assertEquals(180, app3.get("borrowDays").asInt());
        // 修复项目走完全程，CONDITIONAL 验收（部分成功）
        long project3 = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-LAP-3\",\"relicId\":" + relicThree
                + ",\"client\":\"修复部\",\"plan\":\"局部补全\"}")).get("id").asLong();
        long planV = dataOf(postJson("/api/repair-projects/" + project3 + "/plan-versions", "{"
                + "\"planContent\":\"局部补全方案\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"修复部\"}")).get("version").get("id").asLong();
        // 三级会签顺序：INTERNAL -> CUSTODIAN -> EXTERNAL
        expectOk(postJson("/api/plan-versions/" + planV + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}"));
        expectOk(postJson("/api/plan-versions/" + planV + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}"));
        expectOk(postJson("/api/plan-versions/" + planV + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}"));
        expectOk(postEmpty("/api/repair-projects/" + project3 + "/start"));
        expectOk(postJson("/api/repair-projects/" + project3 + "/stages",
                "{\"stageName\":\"清洗\",\"stageResult\":\"完成\",\"operator\":\"修复师甲\"}"));
        expectOk(postEmpty("/api/repair-projects/" + project3 + "/finish"));
        expectOk(postJson("/api/repair-projects/" + project3 + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"opinion\":\"部分成功，局部留观\"}"));

        // 保护等级补偿：THREE -> TWO，文物档案同步调整
        assertEquals("TWO", dataOf(getJson("/api/relics/" + relicThree))
                .get("relic").get("preservationGrade").asText());
        // 借阅申请补偿：180 天 -> 90 天（二级上限）
        JsonNode app3After = dataOf(getJson("/api/loan-applications/" + app3Id));
        assertEquals(90, app3After.get("application").get("borrowDays").asInt(),
                "审批中申请天数应按新等级上限压缩");
        // 补偿记录成对对应：同 compensate_no，LOAN 行 pair_id 指向 GRADE 行
        JsonNode comps = dataOf(getJson("/api/loan-compensations?relicId=" + relicThree));
        assertEquals(1, comps.size());
        JsonNode compGroup = comps.get(0);
        assertEquals("CP-RP-LAP-3", compGroup.get("compensateNo").asText());
        JsonNode gradeRow = compGroup.get("grade");
        assertEquals("GRADE", gradeRow.get("side").asText());
        assertEquals("THREE", gradeRow.get("fromGrade").asText());
        assertEquals("TWO", gradeRow.get("toGrade").asText());
        assertEquals("ADJUST_GRADE", gradeRow.get("action").asText());
        JsonNode loanRows = compGroup.get("loans");
        assertEquals(1, loanRows.size());
        JsonNode loanRow = loanRows.get(0);
        assertEquals("LOAN", loanRow.get("side").asText());
        assertEquals(gradeRow.get("id").asLong(), loanRow.get("pairId").asLong(),
                "借阅申请补偿必须指向保护等级补偿，彼此对应");
        assertEquals(compGroup.get("compensateNo").asText(), loanRow.get("compensateNo").asText());
        assertEquals(180, loanRow.get("fromDays").asInt());
        assertEquals(90, loanRow.get("toDays").asInt());
        assertEquals("CLAMP_DAYS", loanRow.get("action").asText());
        assertEquals(app3Id, loanRow.get("applicationId").asLong());

        // 补偿后申请按压缩后天数继续审批通过（快照等级仍为申请时三级）
        expectOk(approveJson(app3Id, "CURATOR", "孙保管", "APPROVE"));
        JsonNode app3Done = dataOf(getJson("/api/loan-applications/" + app3Id + "?hallId=" + hallA));
        assertEquals("APPROVED", app3Done.get("application").get("status").asText());
        assertEquals(90, app3Done.get("snapshots").get(0).get("approvedDays").asInt());
        assertEquals("THREE", app3Done.get("snapshots").get(0).get("grade").asText(),
                "快照固化申请时等级，不随后续补偿改写");

        // ---- 归还后不能再延期 ----
        long loan3Id = app3Done.get("loan").get("id").asLong();
        expectOk(postJson("/api/loans/" + loan3Id + "/return",
                "{\"receiver\":\"库房管理员\",\"returnCondition\":\"完好\"}"));
        expectFail(extendJson(app3Id, "LAP-EXT-Z", 5, "归还后延期", "美院研究部"), "已归还");

        // ---- 台账动作链完整 ----
        Set<String> actions1 = ledgerActions(relicOne);
        assertTrue(actions1.contains("LOAN_APPLY"));
        assertTrue(actions1.contains("LOAN_APPROVE"));
        assertTrue(actions1.contains("LOAN_SNAPSHOT"));
        assertTrue(actions1.contains("LOAN_EXTEND"));
        Set<String> actions2 = ledgerActions(relicTwo);
        assertTrue(actions2.contains("LOAN_REJECT"));
        Set<String> actions3 = ledgerActions(relicThree);
        assertTrue(actions3.contains("LOAN_COMPENSATE"));
        assertTrue(actions3.contains("ACCEPT"));
    }

    // ------------------------------------------------------------------

    private MvcResult applyJson(String applyNo, long relicId, long hallId,
                                String scenario, int days, String applicant) throws Exception {
        return postJson("/api/loan-applications", "{"
                + "\"applyNo\":\"" + applyNo + "\",\"relicId\":" + relicId
                + ",\"hallId\":" + hallId + ",\"scenario\":\"" + scenario + "\","
                + "\"borrowDays\":" + days + ",\"applicant\":\"" + applicant + "\","
                + "\"purpose\":\"测试用途\",\"insuranceAmount\":500000,"
                + "\"insurer\":\"平安保险\",\"policyNo\":\"POL-001\"}");
    }

    private MvcResult approveJson(long applicationId, String role, String name,
                                  String decision) throws Exception {
        return postJson("/api/loan-applications/" + applicationId + "/approve", "{"
                + "\"approverRole\":\"" + role + "\",\"approverName\":\"" + name + "\","
                + "\"decision\":\"" + decision + "\",\"opinion\":\"测试意见\"}");
    }

    private MvcResult extendJson(long parentId, String applyNo, int days,
                                 String reason, String applicant) throws Exception {
        return postJson("/api/loan-applications/" + parentId + "/extensions", "{"
                + "\"applyNo\":\"" + applyNo + "\",\"extendDays\":" + days + ","
                + "\"reason\":\"" + reason + "\",\"applicant\":\"" + applicant + "\"}");
    }

    private Set<String> ledgerActions(long relicId) throws Exception {
        Set<String> actions = new HashSet<>();
        for (JsonNode entry : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            actions.add(entry.get("action").asText());
        }
        return actions;
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        MockHttpServletRequestBuilder request = post(uri).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
