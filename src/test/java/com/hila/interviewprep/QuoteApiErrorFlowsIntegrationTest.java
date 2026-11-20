package com.hila.interviewprep;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full application with a fixed clock (2026-11-19T10:00Z): pricing, validation, rules and errors wired together. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(QuoteApiErrorFlowsIntegrationTest.FixedClock.class)
class QuoteApiErrorFlowsIntegrationTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final String WORKED_EXAMPLE = """
            {"petType": "DOG", "breed": "Pug", "birthDate": "2023-11-19",
             "plan": "PREMIUM", "deductible": 250, "country": "US"}
            """;

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void workedExampleIsPricedEndToEnd() throws Exception {
        String location = postBody(WORKED_EXAMPLE)
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"monthlyPremium\":49.14")))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-19T10:00:00Z"))
                .andExpect(jsonPath("$.validUntil").value("2026-12-19T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"monthlyPremium\":49.14")));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postBody("{\"petType\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Malformed JSON request body"));
    }

    @Test
    void strictJsonIsActiveInTheRealApplication() throws Exception {
        postBody(WORKED_EXAMPLE.replace("250", "\"250\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'deductible'"));
    }

    @Test
    void missingFieldsAre400WithEveryFieldListed() throws Exception {
        postBody("{\"petType\": \"DOG\", \"country\": \"US\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("birthDate"))
                .andExpect(jsonPath("$.errors[1].field").value("deductible"))
                .andExpect(jsonPath("$.errors[2].field").value("plan"));
    }

    @Test
    void petTooOldIs422() throws Exception {
        postBody(WORKED_EXAMPLE.replace("2023-11-19", "2010-01-01"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Pet must be at most 14 years old"));
    }

    @Test
    void unsupportedDeductibleIs422() throws Exception {
        postBody(WORKED_EXAMPLE.replace("250", "300"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Deductible 300 is not supported. Allowed: 100, 250, 500"));
    }

    @Test
    void validRequestStillWorksAfterRejectedOnes() throws Exception {
        postBody("not json").andExpect(status().isBadRequest());
        postBody(WORKED_EXAMPLE.replace("\"US\"", "\"us\"")).andExpect(status().isUnprocessableContent());

        postBody(WORKED_EXAMPLE)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/quotes/")));
    }
}
