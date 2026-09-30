package com.radhikafarms.milkweb.product;

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

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private ProductService productService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ownerCanCreateProduct() throws Exception {
        String token = createOwnerToken("9100000001");

        mockMvc.perform(post("/api/owner/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("Cow Milk", "Fresh milk", "LITRE", "60.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/owner/products/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Cow Milk"))
                .andExpect(jsonPath("$.description").value("Fresh milk"))
                .andExpect(jsonPath("$.unit").value("LITRE"))
                .andExpect(jsonPath("$.price").value(60.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.nameKey").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.subscriptions").doesNotExist());
    }

    @Test
    void ownerCanListActiveAndInactiveProducts() throws Exception {
        String token = createOwnerToken("9100000002");
        Product active = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", "Fresh", ProductUnit.LITRE, new BigDecimal("60.00")));
        Product inactive = productService.createProduct(new OwnerCreateProductRequest(
                "Ghee", null, ProductUnit.KILOGRAM, new BigDecimal("700.00")));
        productService.updateProduct(inactive.getId(), new OwnerUpdateProductRequest(
                "Ghee", null, ProductUnit.KILOGRAM, new BigDecimal("700.00"), ProductStatus.INACTIVE));

        mockMvc.perform(get("/api/owner/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id=='" + active.getId() + "')].name")
                        .value(org.hamcrest.Matchers.contains("Cow Milk")))
                .andExpect(jsonPath("$[?(@.id=='" + inactive.getId() + "')].status")
                        .value(org.hamcrest.Matchers.contains("INACTIVE")));
    }

    @Test
    void ownerCanGetProductAndUnknownProductReturns404() throws Exception {
        String token = createOwnerToken("9100000003");
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Buffalo Milk", "Rich milk", ProductUnit.LITRE, new BigDecimal("75.50")));

        mockMvc.perform(get("/api/owner/products/{productId}", product.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.name").value("Buffalo Milk"))
                .andExpect(jsonPath("$.price").value(75.50))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist());

        mockMvc.perform(get("/api/owner/products/{productId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanUpdateProductWithoutChangingIdentityOrCreatedAt() throws Exception {
        String token = createOwnerToken("9100000004");
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", "Old description", ProductUnit.LITRE, new BigDecimal("60.00")));
        UUID productId = product.getId();
        String createdAt = product.getCreatedAt().toString();

        mockMvc.perform(put("/api/owner/products/{productId}", productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("Farm Cheese", "Cheese", "KILOGRAM", "450.25", "INACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.name").value("Farm Cheese"))
                .andExpect(jsonPath("$.description").value("Cheese"))
                .andExpect(jsonPath("$.unit").value("KILOGRAM"))
                .andExpect(jsonPath("$.price").value(450.25))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.nameKey").doesNotExist())
                .andExpect(jsonPath("$.subscriptions").doesNotExist());
    }

    @Test
    void duplicateProductNameReturns409OnCreateAndUpdate() throws Exception {
        String token = createOwnerToken("9100000005");
        Product first = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", null, ProductUnit.LITRE, new BigDecimal("60.00")));
        Product second = productService.createProduct(new OwnerCreateProductRequest(
                "Buffalo Milk", null, ProductUnit.LITRE, new BigDecimal("75.00")));

        mockMvc.perform(post("/api/owner/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("Cow Milk", null, "LITRE", "80.00")))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/owner/products/{productId}", second.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("Cow Milk", null, "LITRE", "80.00", "ACTIVE")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/owner/products/{productId}", first.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cow Milk"));
    }

    @Test
    void invalidProductRequestsReturn400() throws Exception {
        String token = createOwnerToken("9100000006");
        String[] invalidCreateRequests = {
                "{\"name\":\"\",\"unit\":\"LITRE\",\"price\":1.00}",
                "{\"unit\":\"LITRE\",\"price\":1.00}",
                "{\"name\":\"Milk\",\"price\":1.00}",
                "{\"name\":\"Milk\",\"unit\":\"LITRE\"}",
                "{\"name\":\"Milk\",\"unit\":\"LITRE\",\"price\":-1.00}",
                "{\"name\":\"Milk\",\"unit\":\"LITRE\",\"price\":1.001}",
                "{\"name\":\"Milk\",\"unit\":\"LITRE\",\"price\":10000000000.00}",
                "{\"name\":\"Milk\",\"unit\":\"INVALID\",\"price\":1.00}",
                "{\"name\":\"" + "x".repeat(101) + "\",\"unit\":\"LITRE\",\"price\":1.00}",
                "{\"name\":\"Milk\",\"description\":\"" + "x".repeat(501)
                        + "\",\"unit\":\"LITRE\",\"price\":1.00}"
        };

        for (String request : invalidCreateRequests) {
            mockMvc.perform(post("/api/owner/products")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(put("/api/owner/products/{productId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Milk","unit":"LITRE","price":1.00}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/owner/products/{productId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("Milk", null, "LITRE", "1.00", "INVALID")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedProductUuidReturns400() throws Exception {
        String token = createOwnerToken("9100000007");

        mockMvc.perform(get("/api/owner/products/not-a-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotAccessOwnerProductEndpoints() throws Exception {
        String token = createCustomerToken("9100000008");
        String createBody = createRequest("Milk", null, "LITRE", "1.00");
        String updateBody = updateRequest("Milk", null, "LITRE", "1.00", "ACTIVE");

        mockMvc.perform(post("/api/owner/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/products/{productId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/owner/products/{productId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallersCannotAccessOwnerProductEndpoints() throws Exception {
        String createBody = createRequest("Milk", null, "LITRE", "1.00");
        String updateBody = updateRequest("Milk", null, "LITRE", "1.00", "ACTIVE");

        mockMvc.perform(post("/api/owner/products")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/products"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner/products/{productId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/owner/products/{productId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isUnauthorized());
    }

    private String createOwnerToken(String phone) throws Exception {
        return createToken(phone, UserRole.OWNER);
    }

    private String createCustomerToken(String phone) throws Exception {
        return createToken(phone, UserRole.CUSTOMER);
    }

    private String createToken(String phone, UserRole role) throws Exception {
        userService.createUser(phone, "testpass123", role);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(phone, "testpass123"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String createRequest(String name, String description, String unit, String price) {
        return """
                {"name":%s,"description":%s,"unit":"%s","price":%s}
                """.formatted(jsonString(name), jsonString(description), unit, price);
    }

    private String updateRequest(String name, String description, String unit, String price, String status) {
        return """
                {"name":%s,"description":%s,"unit":"%s","price":%s,"status":"%s"}
                """.formatted(jsonString(name), jsonString(description), unit, price, status);
    }

    private String jsonString(String value) {
        return value == null ? "null" : objectMapper.valueToTree(value).toString();
    }
}
