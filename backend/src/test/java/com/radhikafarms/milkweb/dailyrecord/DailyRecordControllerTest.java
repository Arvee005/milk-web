package com.radhikafarms.milkweb.dailyrecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.auth.LoginRequest;
import com.radhikafarms.milkweb.auth.JwtService;
import com.radhikafarms.milkweb.customer.*;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DailyRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private Clock businessClock;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ownerCreatesDailyRecordWithServerCalculatedFieldsAndDtoBoundary() throws Exception {
        String token = ownerToken("9400000001");
        Customer customer = customer();
        Product product = product();
        LocalDate today = LocalDate.now();

        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), null, today,
                                "MORNING", "COLLECTED", "2.500", "60.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/owner/daily-records/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.productId").value(product.getId().toString()))
                .andExpect(jsonPath("$.subscriptionId").doesNotExist())
                .andExpect(jsonPath("$.recordDate").value(today.toString()))
                .andExpect(jsonPath("$.slot").value("MORNING"))
                .andExpect(jsonPath("$.quantity").value(2.5))
                .andExpect(jsonPath("$.unitPriceSnapshot").value(60.0))
                .andExpect(jsonPath("$.amount").value(150.0))
                .andExpect(jsonPath("$.status").value("COLLECTED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.product").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void clientCannotControlIdAmountOrTimestamps() throws Exception {
        String token = ownerToken("9400000002");
        Customer customer = customer();
        Product product = product();
        UUID suppliedId = UUID.randomUUID();
        String body = """
                {"id":"%s","customerId":"%s","productId":"%s","recordDate":"%s",
                 "slot":"EVENING","quantity":1.25,"unitPriceSnapshot":40.00,"amount":0.01,
                 "status":"EXTRA","createdAt":"2000-01-01T00:00:00Z","updatedAt":"2000-01-01T00:00:00Z"}
                """.formatted(suppliedId, customer.getId(), product.getId(), LocalDate.now());

        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(suppliedId.toString())))
                .andExpect(jsonPath("$.amount").value(50.0))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.not("2000-01-01T00:00:00Z")));
    }

    @Test
    void ownerCanListWithFiltersPaginationAndStableOrdering() throws Exception {
        String token = ownerToken("9400000003");
        Customer customer = customer();
        Product product = product();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();
        createRecord(customer, product, yesterday, DailyRecordSlot.EVENING, DailyRecordStatus.SKIPPED, "0", null);
        DailyRecord morning = createRecord(customer, product, today,
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1", "60.00");
        DailyRecord evening = createRecord(customer, product, today,
                DailyRecordSlot.EVENING, DailyRecordStatus.EXTRA, "2", "60.00");

        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("customerId", customer.getId().toString())
                        .param("productId", product.getId().toString())
                        .param("fromDate", yesterday.toString())
                        .param("toDate", today.toString())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].recordDate").value(today.toString()))
                .andExpect(jsonPath("$.content[0].slot").value("EVENING"))
                .andExpect(jsonPath("$.content[0].id").value(evening.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(morning.getId().toString()));

        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("recordDate", today.toString())
                        .param("slot", "EVENING")
                        .param("status", "EXTRA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(evening.getId().toString()));
    }

    @Test
    void customerCanListOnlyTheirOwnRecordsWithProductDetails() throws Exception {
        Customer customer = customer();
        Customer otherCustomer = customer();
        Product product = product();
        LocalDate today = LocalDate.now();
        DailyRecord own = createRecord(customer, product, today,
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1.500", "60.00");
        createRecord(otherCustomer, product, today,
                DailyRecordSlot.MORNING, DailyRecordStatus.EXTRA, "2.000", "70.00");
        String token = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());

        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("recordDate", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(own.getId().toString()))
                .andExpect(jsonPath("$.content[0].recordDate").value(today.toString()))
                .andExpect(jsonPath("$.content[0].slot").value("MORNING"))
                .andExpect(jsonPath("$.content[0].productName").value(product.getName()))
                .andExpect(jsonPath("$.content[0].productUnit").value("LITRE"))
                .andExpect(jsonPath("$.content[0].quantity").value(1.5))
                .andExpect(jsonPath("$.content[0].unitPriceSnapshot").value(60.0))
                .andExpect(jsonPath("$.content[0].amount").value(90.0))
                .andExpect(jsonPath("$.content[0].status").value("COLLECTED"))
                .andExpect(jsonPath("$.content[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].product").doesNotExist())
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
    }

    @Test
    void customerIdQueryParameterCannotBroadenCustomerRecordAccess() throws Exception {
        Customer customer = customer();
        Customer otherCustomer = customer();
        Product product = product();
        LocalDate today = LocalDate.now();
        createRecord(customer, product, today,
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1", "60.00");
        createRecord(otherCustomer, product, today,
                DailyRecordSlot.EVENING, DailyRecordStatus.EXTRA, "2", "70.00");
        String token = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());

        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("customerId", otherCustomer.getId().toString())
                        .param("recordDate", today.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerDailyRecordDateRangeSlotFiltersAndPaginationAreApplied() throws Exception {
        Customer customer = customer();
        Product product = product();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();
        createRecord(customer, product, yesterday,
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1", "60.00");
        DailyRecord latestMorning = createRecord(customer, product, today,
                DailyRecordSlot.MORNING, DailyRecordStatus.EXTRA, "2", "70.00");
        createRecord(customer, product, today,
                DailyRecordSlot.EVENING, DailyRecordStatus.SKIPPED, "0", null);
        String token = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());

        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("fromDate", yesterday.toString())
                        .param("toDate", today.toString())
                        .param("slot", "MORNING")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(latestMorning.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("EXTRA"));

        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("recordDate", today.toString())
                        .param("status", "SKIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SKIPPED"));
    }

    @Test
    void customerDailyRecordFiltersRejectInvalidRangesPaginationAndEnums() throws Exception {
        Customer customer = customer();
        String token = jwtService.generateToken(
                userService.findByPhone(customer.getPhone()).orElseThrow());
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("fromDate", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("recordDate", today.toString())
                        .param("toDate", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("fromDate", today.toString())
                        .param("toDate", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("fromDate", today.minusDays(366).toString())
                        .param("toDate", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("slot", "NIGHT"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "DELIVERED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerDailyRecordEndpointRequiresCustomerRoleAndAuthentication() throws Exception {
        String ownerToken = ownerToken("9400000012");
        mockMvc.perform(get("/api/customer/daily-records")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer/daily-records"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void collectionDateRangeFiltersRejectConflictingOrInvalidRanges() throws Exception {
        String token = ownerToken("9400000004");
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("recordDate", "2026-01-01")
                        .param("fromDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("fromDate", "2026-02-01")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .param("slot", "NIGHT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanGetDailyRecordAndUnknownOrMalformedIdsAreHandled() throws Exception {
        String token = ownerToken("9400000005");
        Customer customer = customer();
        Product product = product();
        DailyRecord record = createRecord(customer, product, LocalDate.now(),
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1", "60.00");

        mockMvc.perform(get("/api/owner/daily-records/{dailyRecordId}", record.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(record.getId().toString()))
                .andExpect(jsonPath("$.amount").value(60.0));
        mockMvc.perform(get("/api/owner/daily-records/{dailyRecordId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/owner/daily-records/not-a-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCreateRequestsReturn400() throws Exception {
        String token = ownerToken("9400000006");
        Customer customer = customer();
        Product product = product();
        String[][] invalid = {
                {"{}", "missing required values"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "COLLECTED", "0", "60.00"), "zero supplied quantity"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "EXTRA", "-1", "60.00"), "negative"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "COLLECTED", "1.0001", "60.00"), "quantity scale"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "COLLECTED", "1", null), "missing price"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "COLLECTED", "1", "-1.00"), "negative price"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "COLLECTED", "1", "60.001"), "price scale"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "SKIPPED", "0", "0.00"), "price on skipped"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now().plusDays(1),
                        "MORNING", "COLLECTED", "1", "60.00"), "future date"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "NIGHT", "COLLECTED", "1", "60.00"), "invalid slot"},
                {createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                        "MORNING", "INVALID", "1", "60.00"), "invalid status"},
                {"{\"customerId\":\"bad\",\"productId\":\"%s\"}".formatted(product.getId()), "invalid uuid"},
                {"{\"customerId\":\"%s\",\"productId\":\"%s\",\"recordDate\":\"bad\"}"
                        .formatted(customer.getId(), product.getId()), "invalid date"}
        };
        for (String[] invalidRequest : invalid) {
            mockMvc.perform(post("/api/owner/daily-records")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidRequest[0]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void duplicateAndEligibilityConflictsReturn409() throws Exception {
        String token = ownerToken("9400000007");
        Customer customer = customer();
        Product product = product();
        String body = createBody(customer.getId(), product.getId(), null, LocalDate.now(),
                "MORNING", "EXTRA", "1", "60.00");
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        Customer paused = customer();
        paused.setStatus(CustomerStatus.PAUSED);
        customerRepository.save(paused);
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(paused.getId(), product.getId(), null, LocalDate.now(),
                                "MORNING", "COLLECTED", "1", "60.00")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), product.getId(), null, LocalDate.now(),
                                "EVENING", "COLLECTED", "1", "60.00")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), UUID.randomUUID(), null, LocalDate.now(),
                                "EVENING", "COLLECTED", "1", "60.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownSubscriptionAndInactiveProductAreMappedCorrectly() throws Exception {
        String token = ownerToken("9400000011");
        Customer customer = customer();
        Product activeProduct = product();

        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), activeProduct.getId(), UUID.randomUUID(),
                                LocalDate.now(), "MORNING", "COLLECTED", "1", "60.00")))
                .andExpect(status().isNotFound());

        Product inactiveProduct = productService.updateProduct(activeProduct.getId(),
                new OwnerUpdateProductRequest(activeProduct.getName(), activeProduct.getDescription(),
                        activeProduct.getUnit(), activeProduct.getPrice(), ProductStatus.INACTIVE));
        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), inactiveProduct.getId(), null,
                                LocalDate.now(), "EVENING", "EXTRA", "1", "60.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerCanUpdateTodayButPastRecordUpdateConflicts() throws Exception {
        String token = ownerToken("9400000008");
        Customer customer = customer();
        Product product = product();
        LocalDate todayDate = LocalDate.now(businessClock);
        DailyRecord today = createRecord(customer, product, todayDate,
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "2", "60.00");

        mockMvc.perform(put("/api/owner/daily-records/{dailyRecordId}", today.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1.5", "COLLECTED", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(1.5))
                .andExpect(jsonPath("$.unitPriceSnapshot").value(60.0))
                .andExpect(jsonPath("$.amount").value(90.0));

        DailyRecord past = createRecord(customer, product(), todayDate.minusDays(1),
                DailyRecordSlot.MORNING, DailyRecordStatus.COLLECTED, "1", "60.00");
        mockMvc.perform(put("/api/owner/daily-records/{dailyRecordId}", past.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2", "COLLECTED", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void noSupplyRecordCanBecomeChargeableOnlyWithNewSnapshot() throws Exception {
        String token = ownerToken("9400000009");
        Customer customer = customer();
        Product product = product();
        DailyRecord skipped = createRecord(customer, product, LocalDate.now(businessClock),
                DailyRecordSlot.MORNING, DailyRecordStatus.SKIPPED, "0", null);

        mockMvc.perform(put("/api/owner/daily-records/{dailyRecordId}", skipped.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1", "COLLECTED", "60.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPriceSnapshot").value(60.0))
                .andExpect(jsonPath("$.amount").value(60.0));

        mockMvc.perform(put("/api/owner/daily-records/{dailyRecordId}", skipped.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2", "COLLECTED", "61.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void customerAndUnauthenticatedCannotAccessOwnerDailyRecords() throws Exception {
        String customerToken = token("9400000010", UserRole.CUSTOMER);
        String body = createBody(UUID.randomUUID(), UUID.randomUUID(), null, LocalDate.now(),
                "MORNING", "SKIPPED", "0", null);

        mockMvc.perform(post("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/daily-records")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/daily-records/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/owner/daily-records/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("0", "SKIPPED", null)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/owner/daily-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/daily-records"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/daily-records/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/owner/daily-records/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("0", "SKIPPED", null)))
                .andExpect(status().isUnauthorized());
    }

    private Customer customer() {
        String phone = "94" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", "Daily Controller Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now()));
    }

    private Product product() {
        return productService.createProduct(new OwnerCreateProductRequest(
                "Daily Controller Product " + UUID.randomUUID(), null,
                ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private DailyRecord createRecord(
            Customer customer, Product product, LocalDate date,
            DailyRecordSlot slot, DailyRecordStatus status, String quantity, String price
    ) {
        return dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, date, slot,
                new BigDecimal(quantity), price == null ? null : new BigDecimal(price), status));
    }

    private String createBody(
            UUID customerId, UUID productId, UUID subscriptionId, LocalDate date,
            String slot, String status, String quantity, String price
    ) {
        return """
                {"customerId":"%s","productId":"%s","subscriptionId":%s,"recordDate":"%s",
                 "slot":"%s","quantity":%s,"unitPriceSnapshot":%s,"status":"%s"}
                """.formatted(customerId, productId,
                subscriptionId == null ? "null" : "\"" + subscriptionId + "\"",
                date, slot, quantity, price == null ? "null" : price, status);
    }

    private String updateBody(String quantity, String status, String price) {
        return """
                {"quantity":%s,"status":"%s","unitPriceSnapshot":%s}
                """.formatted(quantity, status, price == null ? "null" : price);
    }

    private String ownerToken(String phone) throws Exception {
        return token(phone, UserRole.OWNER);
    }

    private String token(String phone, UserRole role) throws Exception {
        userService.createUser(phone, "testpass123", role);
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(phone, "testpass123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginJson).get("token").asText();
    }
}
