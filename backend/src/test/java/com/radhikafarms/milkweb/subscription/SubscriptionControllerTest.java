package com.radhikafarms.milkweb.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.auth.LoginRequest;
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
class SubscriptionControllerTest {

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
    private SubscriptionService subscriptionService;

    @Autowired
    private Clock businessClock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ownerCreatesSubscriptionWithDefaultActiveAndDtoBoundary() throws Exception {
        String token = ownerToken("9700000001");
        Customer customer = createCustomer("9700000101");
        Product product = createProduct("Controller Cow Milk");
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = start.plusDays(20);

        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), "10.125", start, end)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/owner/subscriptions/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.productId").value(product.getId().toString()))
                .andExpect(jsonPath("$.defaultQuantity").value(10.125))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startDate").value(start.toString()))
                .andExpect(jsonPath("$.endDate").value(end.toString()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.product").doesNotExist())
                .andExpect(jsonPath("$.price").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void ownerCanGetAndListSubscriptionsWithFilters() throws Exception {
        String token = ownerToken("9700000002");
        Customer customer = createCustomer("9700000102");
        Product product = createProduct("Controller Filter Milk");
        Subscription first = createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        Product otherProduct = createProduct("Controller Filter Ghee");
        Subscription second = createSubscription(customer, otherProduct, "2",
                LocalDate.of(2026, 2, 1), null);

        mockMvc.perform(get("/api/owner/subscriptions/{subscriptionId}", first.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.productId").value(product.getId().toString()));

        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].customer").doesNotExist());

        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .param("customerId", customer.getId().toString())
                        .param("productId", product.getId().toString())
                        .param("status", "ACTIVE")
                        .param("effectiveOn", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first.getId().toString()));

        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .param("customerId", customer.getId().toString())
                        .param("productId", otherProduct.getId().toString())
                        .param("status", "ACTIVE")
                        .param("effectiveOn", "2026-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(second.getId().toString()));
    }

    @Test
    void unknownSubscriptionAndMalformedPathOrFilterReturnExpectedStatuses() throws Exception {
        String token = ownerToken("9700000003");

        mockMvc.perform(get("/api/owner/subscriptions/{subscriptionId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/owner/subscriptions/not-a-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .param("customerId", "bad-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .param("effectiveOn", "bad-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCreateAndUpdateRequestsReturn400() throws Exception {
        String token = ownerToken("9700000004");
        Customer customer = createCustomer("9700000104");
        Product product = createProduct("Controller Validation Milk");
        String[][] invalidBodies = {
                {"{\"productId\":\"%s\",\"defaultQuantity\":1,\"startDate\":\"2026-10-01\"}"
                        .formatted(product.getId()), "missing customer"},
                {"{\"customerId\":\"%s\",\"defaultQuantity\":1,\"startDate\":\"2026-10-01\"}"
                        .formatted(customer.getId()), "missing product"},
                {"{\"customerId\":\"%s\",\"productId\":\"%s\",\"startDate\":\"2026-10-01\"}"
                        .formatted(customer.getId(), product.getId()), "missing quantity"},
                {createBody(customer.getId(), product.getId(), "0", LocalDate.now(), null), "zero quantity"},
                {createBody(customer.getId(), product.getId(), "-1", LocalDate.now(), null), "negative quantity"},
                {createBody(customer.getId(), product.getId(), "1.0001", LocalDate.now(), null), "precision"},
                {"{\"customerId\":\"%s\",\"productId\":\"%s\",\"defaultQuantity\":1}"
                        .formatted(customer.getId(), product.getId()), "missing start date"},
                {createBody(customer.getId(), product.getId(), "1",
                        LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 1)), "invalid range"},
                {"{\"customerId\":\"%s\",\"productId\":\"%s\",\"defaultQuantity\":1,\"startDate\":\"bad\"}"
                        .formatted(customer.getId(), product.getId()), "invalid date"}
        };
        for (String[] invalid : invalidBodies) {
            mockMvc.perform(post("/api/owner/subscriptions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(invalid[0]))
                    .andExpect(status().isBadRequest());
        }

        Subscription subscription = createSubscription(customer, product, "1",
                LocalDate.now().plusDays(5), null);
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultQuantity":1,"startDate":"2027-01-01","endDate":null}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1", subscription.getStartDate(), null, "INVALID")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1.0001", subscription.getStartDate(), null, "ACTIVE")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), "1",
                                LocalDate.now().plusDays(5), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAndInactiveCustomerOrProductAreMappedCorrectly() throws Exception {
        String token = ownerToken("9700000005");
        Customer customer = createCustomer("9700000105");
        Product product = createProduct("Controller Eligibility Milk");

        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), product.getId(), "1", LocalDate.now(), null)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), UUID.randomUUID(), "1", LocalDate.now(), null)))
                .andExpect(status().isNotFound());

        customer.setStatus(CustomerStatus.INACTIVE);
        customerRepository.save(customer);
        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), "1", LocalDate.now(), null)))
                .andExpect(status().isConflict());
    }

    @Test
    void overlappingAndAdjacentPeriodsFollowInclusiveDateRules() throws Exception {
        String token = ownerToken("9700000006");
        Customer customer = createCustomer("9700000106");
        Product product = createProduct("Controller Overlap Milk");
        createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), "15",
                                LocalDate.of(2026, 1, 31), null)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(customer.getId(), product.getId(), "15",
                                LocalDate.of(2026, 2, 1), null)))
                .andExpect(status().isCreated());
    }

    @Test
    void ownerCanUpdateFuturePlanButCannotChangeItsCustomerOrProduct() throws Exception {
        String token = ownerToken("9700000007");
        Customer customer = createCustomer("9700000107");
        Product product = createProduct("Controller Update Milk");
        Subscription subscription = createSubscription(customer, product, "10",
                LocalDate.now().plusDays(20), null);
        LocalDate newStart = subscription.getStartDate().plusDays(1);
        String originalCreatedAt = subscription.getCreatedAt().toString();

        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("12.345", newStart, newStart.plusDays(10), "PAUSED")
                                .replace("\"status\":\"PAUSED\"", "\"status\":\"PAUSED\","
                                        + "\"customerId\":\"" + UUID.randomUUID() + "\","
                                        + "\"productId\":\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subscription.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.productId").value(product.getId().toString()))
                .andExpect(jsonPath("$.defaultQuantity").value(12.345))
                .andExpect(jsonPath("$.startDate").value(newStart.toString()))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.createdAt").value(originalCreatedAt));
    }

    @Test
    void startedPlanCannotRewriteQuantityOrStartButCanPauseAndShorten() throws Exception {
        String token = ownerToken("9700000008");
        Customer customer = createCustomer("9700000108");
        Product product = createProduct("Controller Started Milk");
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(1);
        Subscription subscription = createSubscription(customer, product, "10", start,
                today.plusDays(30));

        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("11", start, subscription.getEndDate(), "ACTIVE")))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("10", start.minusDays(1), subscription.getEndDate(), "ACTIVE")))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/owner/subscriptions/{subscriptionId}", subscription.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("10", start, today.plusDays(10), "PAUSED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.endDate").value(today.plusDays(10).toString()));
    }

    @Test
    void customerAndUnauthenticatedUsersCannotUseOwnerEndpoints() throws Exception {
        String customerToken = token("9700000009", UserRole.CUSTOMER);
        Customer customer = createCustomer("9700000109");
        Product product = createProduct("Security Milk");
        String body = createBody(customer.getId(), product.getId(), "1", LocalDate.now().plusDays(5), null);

        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/subscriptions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/owner/subscriptions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1", LocalDate.now().plusDays(5), null, "ACTIVE")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/owner/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/subscriptions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/subscriptions/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/owner/subscriptions/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1", LocalDate.now().plusDays(5), null, "ACTIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerListsOnlyTheirSubscriptionsAndDateAndStatusBothDetermineEffectiveness() throws Exception {
        Customer customer = createCustomer("9700000110");
        Customer anotherCustomer = createCustomer("9700000111");
        String token = loginToken(customer.getPhone());
        LocalDate today = LocalDate.now(businessClock);

        Subscription current = createSubscription(customer, createProduct("Customer Current Milk"),
                "2.5", today, today);
        Subscription upcoming = createSubscription(customer, createProduct("Customer Upcoming Milk"),
                "1", today.plusDays(1), null);
        Subscription ended = createSubscription(customer, createProduct("Customer Ended Milk"),
                "3", today.minusDays(5), today.minusDays(1));
        Subscription paused = createSubscription(customer, createProduct("Customer Paused Milk"),
                "4", today, null);
        pauseSubscription(paused);
        Subscription cancelled = createSubscription(customer, createProduct("Customer Cancelled Milk"),
                "5", today, null);
        cancelSubscription(cancelled);
        createSubscription(anotherCustomer, createProduct("Other Customer Milk"), "7", today, null);

        mockMvc.perform(get("/api/customer/subscriptions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.id=='" + current.getId() + "')].effectiveToday").value(true))
                .andExpect(jsonPath("$[?(@.id=='" + current.getId() + "')].periodState").value("IN_PERIOD"))
                .andExpect(jsonPath("$[?(@.id=='" + upcoming.getId() + "')].effectiveToday").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + upcoming.getId() + "')].periodState").value("UPCOMING"))
                .andExpect(jsonPath("$[?(@.id=='" + ended.getId() + "')].effectiveToday").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + ended.getId() + "')].periodState").value("ENDED"))
                .andExpect(jsonPath("$[?(@.id=='" + paused.getId() + "')].status").value("PAUSED"))
                .andExpect(jsonPath("$[?(@.id=='" + paused.getId() + "')].effectiveToday").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + cancelled.getId() + "')].status").value("CANCELLED"))
                .andExpect(jsonPath("$[?(@.id=='" + cancelled.getId() + "')].periodState").value("IN_PERIOD"))
                .andExpect(jsonPath("$[?(@.id=='" + cancelled.getId() + "')].effectiveToday").value(false))
                .andExpect(jsonPath("$[*].productName").exists())
                .andExpect(jsonPath("$[*].productUnit").exists())
                .andExpect(jsonPath("$[*].customerId").doesNotExist())
                .andExpect(jsonPath("$[*].productId").doesNotExist())
                .andExpect(jsonPath("$[*].customer").doesNotExist())
                .andExpect(jsonPath("$[*].product").doesNotExist());
    }

    @Test
    void customerSubscriptionEndpointRejectsCustomerIdAndOtherRoles() throws Exception {
        Customer customer = createCustomer("9700000112");
        String customerToken = loginToken(customer.getPhone());
        String owner = ownerToken("9700000012");

        mockMvc.perform(get("/api/customer/subscriptions")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/customer/subscriptions")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer/subscriptions"))
                .andExpect(status().isUnauthorized());
    }

    private void pauseSubscription(Subscription subscription) {
        subscriptionService.updateSubscription(subscription.getId(), new OwnerUpdateSubscriptionRequest(
                subscription.getDefaultQuantity(), subscription.getStartDate(), subscription.getEndDate(),
                SubscriptionStatus.PAUSED));
    }

    private void cancelSubscription(Subscription subscription) {
        subscriptionService.updateSubscription(subscription.getId(), new OwnerUpdateSubscriptionRequest(
                subscription.getDefaultQuantity(), subscription.getStartDate(), subscription.getEndDate(),
                SubscriptionStatus.CANCELLED));
    }

    private String loginToken(String phone) throws Exception {
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(phone, "secret123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginJson).get("token").asText();
    }

    private Customer createCustomer(String phone) {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", "Subscription Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now()));
    }

    private Product createProduct(String name) {
        return productService.createProduct(new OwnerCreateProductRequest(
                name, null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private Subscription createSubscription(
            Customer customer, Product product, String quantity, LocalDate start, LocalDate end
    ) {
        return subscriptionService.createSubscription(new OwnerCreateSubscriptionRequest(
                customer.getId(), product.getId(), new BigDecimal(quantity), start, end));
    }

    private String createBody(UUID customerId, UUID productId, String quantity, LocalDate start, LocalDate end) {
        return """
                {"customerId":"%s","productId":"%s","defaultQuantity":%s,"startDate":"%s","endDate":%s}
                """.formatted(customerId, productId, quantity, start, end == null ? "null" : "\"" + end + "\"");
    }

    private String updateBody(String quantity, LocalDate start, LocalDate end, String status) {
        return """
                {"defaultQuantity":%s,"startDate":"%s","endDate":%s,"status":"%s"}
                """.formatted(quantity, start, end == null ? "null" : "\"" + end + "\"", status);
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
