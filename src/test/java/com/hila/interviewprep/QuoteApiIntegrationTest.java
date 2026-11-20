package com.hila.interviewprep;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuoteApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createdQuoteCanBeFetchedFromItsLocation() throws Exception {
        String body = """
                {
                  "petType": "CAT",
                  "breed": "Siamese",
                  "birthDate": "2025-01-15",
                  "plan": "BASIC",
                  "deductible": 100,
                  "country": "US"
                }
                """;

        MvcResult created = mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        String createdBody = created.getResponse().getContentAsString();

        assertThat(location).matches("http://localhost/quotes/[0-9a-f-]{36}");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().json(createdBody));
    }

    @Test
    void unknownQuoteReturns404() throws Exception {
        mockMvc.perform(get("/quotes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}