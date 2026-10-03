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

    private String token(String phone, UserRole role) {
        return jwtService.generateToken(userService.createUser(phone, "secret123", role));
    }

    private String phone() {
        return "97" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
