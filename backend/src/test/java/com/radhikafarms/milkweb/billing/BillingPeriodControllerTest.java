package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.auth.JwtService;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillingPeriodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Clock businessClock;

    @Test
    void ownerCanInspectWithoutCreatingAndClosePeriod() throws Exception {
        String token = ownerToken("9800000001");
        YearMonth completedMonth = YearMonth.of(2000, 3);

        mockMvc.perform(get("/api/owner/billing-periods/{period}", completedMonth)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(completedMonth.toString()))
                .andExpect(jsonPath("$.periodStart").value(completedMonth.atDay(1).toString()))
                .andExpect(jsonPath("$.periodEnd").value(completedMonth.atEndOfMonth().toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.closedByUserId").doesNotExist())
                .andExpect(jsonPath("$.chargeableRecordCount").value(0))
                .andExpect(jsonPath("$.chargeableAmount").value(0.0));

        mockMvc.perform(post("/api/owner/billing-periods/{period}/close", completedMonth)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.closedByUserId").exists())
                .andExpect(jsonPath("$.closedBy").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.chargeableRecordCount").value(0));
    }

    @Test
    void invalidPeriodIdentifiersReturnBadRequest() throws Exception {
        String token = ownerToken("9800000002");
        for (String identifier : new String[]{"2024-00", "2024-13", "24-01", "2024-2", "0000-01"}) {
            mockMvc.perform(get("/api/owner/billing-periods/{period}", identifier)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void inspectionAllowsCurrentAndFuturePeriodsButClosingConflicts() throws Exception {
        String token = ownerToken("9800000006");
        YearMonth currentMonth = YearMonth.now(businessClock);
        for (YearMonth month : new YearMonth[]{currentMonth, currentMonth.plusMonths(1)}) {
            mockMvc.perform(get("/api/owner/billing-periods/{period}", month)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OPEN"));
            mockMvc.perform(post("/api/owner/billing-periods/{period}/close", month)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(containsString("completed calendar months")));
        }
    }

    @Test
    void dailyRecordWritesIntoClosedPeriodReturnConflict() throws Exception {
        String token = ownerToken("9800000004");
        Customer customer = customerService.createCustomer(new OwnerCreateCustomerRequest(
                "9800000005", "secret123", "Closed Period Customer", null,
                BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Closed Period Product", null, ProductUnit.LITRE, new BigDecimal("60.00")));
        LocalDate recordDate = YearMonth.now(businessClock).minusMonths(1).atDay(15);
        String period = YearMonth.from(recordDate).toString();

        mockMvc.perform(post("/api/owner/billing-periods/{period}/close", period)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","productId":"%s","recordDate":"%s",
                                 "slot":"MORNING","quantity":1,"unitPriceSnapshot":60.00,
                                 "status":"COLLECTED"}
                                """.formatted(customer.getId(), product.getId(), recordDate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("Billing period is closed")));
    }

    @Test
    void customerAndUnauthenticatedUsersCannotUseOwnerPeriodEndpoints() throws Exception {
        String customerToken = customerToken("9800000003");
        mockMvc.perform(get("/api/owner/billing-periods/2042-03")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/owner/billing-periods/2042-03/close")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/billing-periods/2042-03"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/owner/billing-periods/2042-03/close")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    private String ownerToken(String phone) {
        return token(phone, UserRole.OWNER);
    }

    private String customerToken(String phone) {
        customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", "Billing API Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, null));
        return jwtService.generateToken(userService.findByPhone(phone).orElseThrow());
    }

    private String token(String phone, UserRole role) {
        return jwtService.generateToken(userService.createUser(phone, "secret123", role));
    }
}
