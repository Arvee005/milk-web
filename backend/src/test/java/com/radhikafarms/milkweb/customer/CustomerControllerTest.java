package com.radhikafarms.milkweb.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.auth.LoginRequest;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerService customerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ownerCanCreateCustomer() throws Exception {
        userService.createUser("9000000001", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000001", "ownerpass");

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543211",
                "secret123",
                "New Customer",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );

        mockMvc.perform(post("/api/owner/customers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("9876543211"))
                .andExpect(jsonPath("$.name").value("New Customer"));
    }

    @Test
    void customerCannotAccessOwnerCreate() throws Exception {
        userService.createUser("9000000002", "custpass", UserRole.CUSTOMER);
        String custToken = loginAndGetToken("9000000002", "custpass");

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543212",
                "secret123",
                "New Customer",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );

        mockMvc.perform(post("/api/owner/customers")
                        .header("Authorization", "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanGetCustomer() throws Exception {
        userService.createUser("9000000003", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000003", "ownerpass");

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543213",
                "secret123",
                "Existing Customer",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );
        Customer created = customerService.createCustomer(req);

        mockMvc.perform(get("/api/owner/customers/{customerId}", created.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.phone").value("9876543213"))
                .andExpect(jsonPath("$.name").value("Existing Customer"));
    }

    @Test
    void ownerGets404ForUnknownCustomer() throws Exception {
        userService.createUser("9000000003", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000003", "ownerpass");

        mockMvc.perform(get("/api/owner/customers/{customerId}", java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanListCustomers() throws Exception {
        userService.createUser("9000000010", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000010", "ownerpass");

        OwnerCreateCustomerRequest r1 = new OwnerCreateCustomerRequest(
                "9876543301",
                "secret123",
                "List Cust 1",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );
        OwnerCreateCustomerRequest r2 = new OwnerCreateCustomerRequest(
                "9876543302",
                "secret123",
                "List Cust 2",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );
        Customer c1 = customerService.createCustomer(r1);
        Customer c2 = customerService.createCustomer(r2);

        mockMvc.perform(get("/api/owner/customers")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.phone=='9876543301')].name")
                        .value(org.hamcrest.Matchers.contains("List Cust 1")))
                .andExpect(jsonPath("$[?(@.phone=='9876543302')].name")
                        .value(org.hamcrest.Matchers.contains("List Cust 2")))
                .andExpect(jsonPath("$[*].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[*].user").doesNotExist());
    }

    @Test
    void ownerGetsEmptyListWhenNoCustomers() throws Exception {
        userService.createUser("9000000011", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000011", "ownerpass");

        mockMvc.perform(get("/api/owner/customers")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()", org.hamcrest.Matchers.is(0)));
    }

    @Test
    void ownerCanUpdateCustomer() throws Exception {
        userService.createUser("9000000012", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000012", "ownerpass");
        Customer created = customerService.createCustomer(new OwnerCreateCustomerRequest(
                "9876543303",
                "secret123",
                "Original Name",
                "Original Address",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                java.time.LocalDate.of(2026, 1, 1)
        ));

        mockMvc.perform(put("/api/owner/customers/{customerId}", created.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestJson("Updated Name", "Updated Address", "PREPAID", "PAUSED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9876543303"))
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.address").value("Updated Address"))
                .andExpect(jsonPath("$.billingType").value("PREPAID"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.joinDate").value("2026-02-02"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void ownerCannotChangePhoneThroughUpdate() throws Exception {
        userService.createUser("9000000013", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000013", "ownerpass");
        Customer created = customerService.createCustomer(new OwnerCreateCustomerRequest(
                "9876543304",
                "secret123",
                "Original Name",
                "Original Address",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                java.time.LocalDate.of(2026, 1, 1)
        ));

        String requestWithPhone = """
                {
                  "phone": "9876543399",
                  "name": "Updated Name",
                  "address": "Updated Address",
                  "billingType": "MONTHLY",
                  "status": "ACTIVE",
                  "joinDate": "2026-02-02"
                }
                """;

        mockMvc.perform(put("/api/owner/customers/{customerId}", created.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9876543304"));
    }

    @Test
    void ownerGets404WhenUpdatingUnknownCustomer() throws Exception {
        userService.createUser("9000000014", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000014", "ownerpass");
        mockMvc.perform(put("/api/owner/customers/{customerId}", java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestJson("Updated Name", "Updated Address", "MONTHLY", "ACTIVE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidUpdateRequestReturns400() throws Exception {
        userService.createUser("9000000015", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000015", "ownerpass");

        String customerId = java.util.UUID.randomUUID().toString();
        String[] invalidRequests = {
                """
                        {"name":"","address":"Address","billingType":"MONTHLY","status":"ACTIVE","joinDate":"2026-02-02"}
                        """,
                """
                        {"name":"Name","address":"Address","status":"ACTIVE","joinDate":"2026-02-02"}
                        """,
                """
                        {"name":"Name","address":"Address","billingType":"MONTHLY","joinDate":"2026-02-02"}
                        """,
                """
                        {"name":"Name","address":"Address","billingType":"MONTHLY","status":"ACTIVE"}
                        """,
                """
                        {"name":"Name","address":"Address","billingType":"MONTHLY","status":"ACTIVE","joinDate":"not-a-date"}
                        """,
                """
                        {"name":"Name","address":"Address","billingType":"INVALID","status":"ACTIVE","joinDate":"2026-02-02"}
                        """
        };

        for (String request : invalidRequests) {
            mockMvc.perform(put("/api/owner/customers/{customerId}", customerId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(put("/api/owner/customers/not-a-uuid")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestJson("Name", "Address", "MONTHLY", "ACTIVE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotGetOwnerCustomerEndpoint() throws Exception {
        userService.createUser("9000000004", "custpass", UserRole.CUSTOMER);
        String custToken = loginAndGetToken("9000000004", "custpass");

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543214",
                "secret123",
                "Customer Name",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );
        Customer created = customerService.createCustomer(req);

        mockMvc.perform(get("/api/owner/customers/{customerId}", created.getId())
                        .header("Authorization", "Bearer " + custToken))
                .andExpect(status().isForbidden());

        // also assert the list endpoint is forbidden for customer
        mockMvc.perform(get("/api/owner/customers")
                        .header("Authorization", "Bearer " + custToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotGetOwnerCustomer() throws Exception {
        mockMvc.perform(get("/api/owner/customers/{customerId}", java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/owner/customers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotUpdateOwnerCustomer() throws Exception {
        userService.createUser("9000000016", "custpass", UserRole.CUSTOMER);
        String custToken = loginAndGetToken("9000000016", "custpass");
        mockMvc.perform(put("/api/owner/customers/{customerId}", java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + custToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestJson("Updated Name", "Updated Address", "MONTHLY", "ACTIVE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotUpdateOwnerCustomer() throws Exception {
        mockMvc.perform(put("/api/owner/customers/{customerId}", java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestJson("Updated Name", "Updated Address", "MONTHLY", "ACTIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/api/customer/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerWithNoRecordGets404() throws Exception {
        userService.createUser("9000000004", "custpass", UserRole.CUSTOMER);
        String token = loginAndGetToken("9000000004", "custpass");

        mockMvc.perform(get("/api/customer/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationFailureReturns400() throws Exception {
        userService.createUser("9000000005", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000005", "ownerpass");

        // invalid phone
        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "123",
                "short",
                "",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );

        mockMvc.perform(post("/api/owner/customers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePhoneReturns409() throws Exception {
        userService.createUser("9000000006", "ownerpass", UserRole.OWNER);
        String ownerToken = loginAndGetToken("9000000006", "ownerpass");

        userService.createUser("9876543299", "somepass", UserRole.CUSTOMER);

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543299",
                "secret123",
                "Dup",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );

        mockMvc.perform(post("/api/owner/customers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    private String loginAndGetToken(String phone, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    private String updateRequestJson(String name, String address, String billingType, String status) {
        return """
                {
                  "name": "%s",
                  "address": "%s",
                  "billingType": "%s",
                  "status": "%s",
                  "joinDate": "2026-02-02"
                }
                """.formatted(name, address, billingType, status);
    }
}
