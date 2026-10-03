package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.auth.JwtService;
import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.dailyrecord.*;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BillingPeriodService billingPeriodService;

    @Autowired
    private BillService billService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductService productService;

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Clock businessClock;

    @Test
    void ownerCanGenerateRetrieveAndIssueBill() throws Exception {
        String ownerPhone = phone();
        String ownerToken = token(ownerPhone, UserRole.OWNER);
        String customerPhone = phone();
        Customer customer = customerService.createCustomer(new OwnerCreateCustomerRequest(
                customerPhone, "secret123", "Controller Bill Customer", null,
                BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
        String customerToken = jwtService.generateToken(userService.findByPhone(customerPhone).orElseThrow());
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Controller Bill Product " + UUID.randomUUID(), null,
                ProductUnit.LITRE, new BigDecimal("45.00")));
        YearMonth month = YearMonth.of(2004, 1);
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, month.atDay(2), DailyRecordSlot.MORNING,
                BigDecimal.ONE, new BigDecimal("45.00"), DailyRecordStatus.COLLECTED));
        billingPeriodService.closePeriod(month.toString(), userService.findByPhone(ownerPhone).orElseThrow().getId());

        String path = "/api/owner/billing-periods/" + month + "/bills/" + customer.getId();
        String billId = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalAmount").value(45.0))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readTree(billId).get("id").asText());

        mockMvc.perform(get("/api/owner/bills/{billId}", id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Controller Bill Customer"));
        mockMvc.perform(post("/api/owner/bills/{billId}/issue", id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issuedByUserId").exists());
        mockMvc.perform(get("/api/owner/bills")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("customerId", customer.getId().toString())
                        .param("period", month.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/owner/bills/{billId}", id)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/bills/{billId}", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedPeriodReturnsBadRequestAndOpenPeriodGenerationConflicts() throws Exception {
        String ownerPhone = phone();
        String ownerToken = token(ownerPhone, UserRole.OWNER);
        Customer customer = customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone(), "secret123", "Open Period Customer", null,
                BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.now(businessClock)));

        mockMvc.perform(post("/api/owner/billing-periods/2004-13/bills/{customerId}", customer.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/owner/billing-periods/0000-01/bills/{customerId}", customer.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/owner/billing-periods/2004-02/bills/{customerId}", customer.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/owner/bills/{billId}/issue", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCanPageOnlyTheirIssuedBillsWithLineSummaries() throws Exception {
        var owner = userService.createUser(phone(), "secret123", UserRole.OWNER);
        Customer customer = customer("Customer Bill Viewer");
        Customer otherCustomer = customer("Other Bill Viewer");

        BillResponse firstIssued = createBill(owner.getId(), customer, YearMonth.of(2004, 1), true);
        BillResponse draft = createBill(owner.getId(), customer, YearMonth.of(2004, 2), false);
        BillResponse otherIssued = createBill(owner.getId(), otherCustomer, YearMonth.of(2004, 3), true);
        BillResponse secondIssued = createBill(owner.getId(), customer, YearMonth.of(2004, 4), true);
        String customerToken = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());

        var firstPage = mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.content[0].issuedAt").exists())
                .andExpect(jsonPath("$.content[0].period").exists())
                .andExpect(jsonPath("$.content[0].periodStart").exists())
                .andExpect(jsonPath("$.content[0].periodEnd").exists())
                .andExpect(jsonPath("$.content[0].totalAmount").value(45.0))
                .andExpect(jsonPath("$.content[0].lines.length()").value(1))
                .andExpect(jsonPath("$.content[0].lines[0].recordDate").exists())
                .andExpect(jsonPath("$.content[0].lines[0].slot").value("MORNING"))
                .andExpect(jsonPath("$.content[0].lines[0].description").exists())
                .andExpect(jsonPath("$.content[0].lines[0].quantity").value(1.0))
                .andExpect(jsonPath("$.content[0].lines[0].unitPrice").value(45.0))
                .andExpect(jsonPath("$.content[0].lines[0].amount").value(45.0))
                .andExpect(jsonPath("$.content[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerName").doesNotExist())
                .andExpect(jsonPath("$.content[0].issuedByUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].lines[0].dailyRecordId").doesNotExist())
                .andReturn();

        String firstPageBillId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(firstPage.getResponse().getContentAsString())
                .at("/content/0/id").asText();
        var allPages = mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ISSUED"))))
                .andReturn();
        var allContent = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(allPages.getResponse().getContentAsString()).path("content");
        org.assertj.core.api.Assertions.assertThat(allContent.get(0).path("id").asText())
                .isEqualTo(firstPageBillId);
        org.assertj.core.api.Assertions.assertThat(allContent.findValuesAsText("id"))
                .containsExactlyInAnyOrder(firstIssued.id().toString(), secondIssued.id().toString());
        org.assertj.core.api.Assertions.assertThat(allContent.toString())
                .doesNotContain(draft.id().toString())
                .doesNotContain(otherIssued.id().toString());

        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("size", "1")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(
                        allContent.get(1).path("id").asText()));
        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void customerBillsEndpointRejectsCustomerIdAndEnforcesRoleAndPagination() throws Exception {
        Customer customer = customer("Customer Bill Security");
        String customerToken = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());
        String ownerToken = token(phone(), UserRole.OWNER);

        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/customer/bills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer/bills"))
                .andExpect(status().isUnauthorized());
    }

    private Customer customer(String name) {
        String phone = phone();
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", name, null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
    }

    private BillResponse createBill(UUID ownerId, Customer customer, YearMonth month, boolean issue) {
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Bill Product " + UUID.randomUUID(), null,
                ProductUnit.LITRE, new BigDecimal("45.00")));
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, month.atDay(2), DailyRecordSlot.MORNING,
                BigDecimal.ONE, new BigDecimal("45.00"), DailyRecordStatus.COLLECTED));
        billingPeriodService.closePeriod(month.toString(), ownerId);
        BillResponse bill = billService.generateDraft(month.toString(), customer.getId());
        return issue ? billService.issue(bill.id(), ownerId) : bill;
    }

    private String token(String phone, UserRole role) {
        return jwtService.generateToken(userService.createUser(phone, "secret123", role));
    }

    private String phone() {
        return "97" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
