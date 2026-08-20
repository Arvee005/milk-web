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
    void customerCanGetOwnProfile() throws Exception {
        // create customer for this user via service
        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9000000003",
                "custpass",
                "Existing",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        );
        // create via service to ensure linkage
        // create via service to ensure linkage
        customerService.createCustomer(req);

        String token = loginAndGetToken("9000000003", "custpass");

        mockMvc.perform(get("/api/customer/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9000000003"))
                .andExpect(jsonPath("$.name").value("Existing"));
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
}
