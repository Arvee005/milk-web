package com.radhikafarms.milkweb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.auth.JwtService;
import com.radhikafarms.milkweb.billing.BillService;
import com.radhikafarms.milkweb.billing.BillingPeriodService;
import com.radhikafarms.milkweb.customer.BillingType;
import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerService;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.customer.OwnerCreateCustomerRequest;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordService;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordSlot;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus;
import com.radhikafarms.milkweb.dailyrecord.OwnerCreateDailyRecordRequest;
import com.radhikafarms.milkweb.product.OwnerCreateProductRequest;
import com.radhikafarms.milkweb.product.ProductService;
import com.radhikafarms.milkweb.product.ProductUnit;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LedgerControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserService userService;
    @Autowired private CustomerService customerService;
    @Autowired private ProductService productService;
    @Autowired private DailyRecordService dailyRecordService;
    @Autowired private BillingPeriodService billingPeriodService;
    @Autowired private BillService billService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String ownerToken;
    private User owner;
    private Customer customer;
    private String customerToken;
    private UUID billId;

    @BeforeEach
    void setUp() {
        owner = userService.createUser(phone(), "secret123", UserRole.OWNER);
        ownerToken = jwtService.generateToken(owner);
        customer = customerService.createCustomer(new OwnerCreateCustomerRequest(phone(), "secret123", "Asha Devi",
                null, BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.of(2007, 1, 1)));
        customerToken = jwtService.generateToken(userService.findByPhone(customer.getPhone()).orElseThrow());
        var product = productService.createProduct(new OwnerCreateProductRequest(
                "Controller Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("250.00")));
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(customer.getId(), product.getId(), null,
                LocalDate.of(2007, 3, 2), DailyRecordSlot.MORNING, BigDecimal.ONE, new BigDecimal("250.00"),
                DailyRecordStatus.COLLECTED));
        billingPeriodService.closePeriod("2007-03", owner.getId());
        billId = billService.issue(billService.generateDraft("2007-03", customer.getId()).id(), owner.getId()).id();
    }

    @Test
    void ownerRecordsAPaymentAndSeesWhereItWent() throws Exception {
        pay(ownerToken, UUID.randomUUID().toString(), Map.of("amount", "300.00", "method", "UPI", "reference", "UTR998"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(300.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.method").value("UPI"))
                .andExpect(jsonPath("$.appliedTo[0].billId").value(billId.toString()))
                .andExpect(jsonPath("$.appliedTo[0].period").value("2007-03"))
                .andExpect(jsonPath("$.appliedTo[0].amount").value(250.00))
                .andExpect(jsonPath("$.keptAsCredit").value(50.00))
                .andExpect(jsonPath("$.account.amountDue").value(0.0))
                .andExpect(jsonPath("$.account.credit").value(50.00))
                .andExpect(jsonPath("$.recordedBy").doesNotExist());

        mockMvc.perform(get("/api/owner/billing-periods/2007-03/balances").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerName").value("Asha Devi"))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].entries[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$[0].entries[0].method").value("UPI"));
        mockMvc.perform(get("/api/owner/accounts").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Asha Devi')].credit").value(50.00));
    }

    @Test
    void rejectsInvalidPaymentsAndMissingRequestKeys() throws Exception {
        pay(ownerToken, null, Map.of("amount", "10.00", "method", "CASH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Idempotency-Key header is required"));
        pay(ownerToken, UUID.randomUUID().toString(), Map.of("amount", "0.00", "method", "CASH"))
                .andExpect(status().isBadRequest());
        pay(ownerToken, UUID.randomUUID().toString(), Map.of("amount", "10.005", "method", "CASH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("2 decimal places")));
        pay(ownerToken, UUID.randomUUID().toString(), Map.of("amount", "10.00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/owner/customers/{id}/payments", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"10.00\",\"method\":\"CASH\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheOwnerCanRecordOrViewOwnerMoneyRoutes() throws Exception {
        pay(customerToken, UUID.randomUUID().toString(), Map.of("amount", "10.00", "method", "CASH"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/owner/customers/{id}/payments", customer.getId())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"10.00\",\"method\":\"CASH\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/accounts").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer/account").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerSeesOnlyTheirOwnAccountWithoutOwnerNotes() throws Exception {
        pay(ownerToken, UUID.randomUUID().toString(),
                Map.of("amount", "100.00", "method", "CASH", "note", "Collected at gate by Ravi"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/customer/account").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.amountDue").value(150.00))
                .andExpect(jsonPath("$.bills[0].status").value("PART_PAID"))
                .andExpect(jsonPath("$.bills[0].entries[0].amount").value(100.00));
        mockMvc.perform(get("/api/customer/transactions").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$[0].note").doesNotExist());
        mockMvc.perform(get("/api/customer/account").param("customerId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerReversesAPaymentWithAReason() throws Exception {
        String body = pay(ownerToken, UUID.randomUUID().toString(), Map.of("amount", "250.00", "method", "CASH"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(post("/api/owner/payments/{id}/reversal", paymentId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/owner/payments/{id}/reversal", paymentId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Entered for the wrong customer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.takenBack[0].amount").value(250.00))
                .andExpect(jsonPath("$.account.amountDue").value(250.00));
        mockMvc.perform(post("/api/owner/payments/{id}/reversal", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerRecordsAdjustmentsAndRefundsThroughTheApi() throws Exception {
        mockMvc.perform(post("/api/owner/customers/{id}/adjustments", customer.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("direction", "CREDIT", "amount", "30.00",
                                "reason", "₹30 off, milk was sour", "billId", billId.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.amountDue").value(220.00));
        mockMvc.perform(post("/api/owner/customers/{id}/refunds", customer.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "5.00", "method", "CASH",
                                "reason", "No credit to return"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("₹0.00")));
        mockMvc.perform(get("/api/owner/billing-periods/2007-13/balances").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    private ResultActions pay(String token, String key, Map<String, String> body) throws Exception {
        var request = post("/api/owner/customers/{id}/payments", customer.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mockMvc.perform(request);
    }

    private static String phone() {
        return "94" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
