package com.hila.interviewprep.controller;

import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.domain.QuoteStatus;
import com.hila.interviewprep.domain.QuoteView;
import com.hila.interviewprep.error.BusinessRuleException;
import com.hila.interviewprep.error.QuoteNotFoundException;
import com.hila.interviewprep.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuoteController.class)
class QuoteControllerTest {

    private static final UUID ID = UUID.fromString("3f1c2b9e-7a4d-4c1e-9b2a-1d2e3f4a5b6c");

    private static final String VALID_BODY = """
            {
              "petType": "DOG",
              "breed": "Pug",
              "birthDate": "2023-05-10",
              "plan": "PREMIUM",
              "deductible": 250,
              "country": "US"
            }
            """;

    private static final QuoteRequest EXPECTED_REQUEST = new QuoteRequest(PetType.DOG, "Pug",
            LocalDate.of(2023, 5, 10), Plan.PREMIUM, new BigDecimal("250"), "US");

    private static final QuoteView VIEW = new QuoteView(
            new Quote(ID, PetType.DOG, "Pug", LocalDate.of(2023, 5, 10), Plan.PREMIUM,
                    new BigDecimal("250"), "US", new BigDecimal("35.00"), "USD",
                    Instant.parse("2026-11-19T10:00:00Z"), Instant.parse("2026-12-19T10:00:00Z")),
            QuoteStatus.ACTIVE);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    @Test
    void createReturns201WithAbsoluteLocationAndFullBody() throws Exception {
        given(quoteService.create(EXPECTED_REQUEST)).willReturn(VIEW);

        mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/quotes/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.petType").value("DOG"))
                .andExpect(jsonPath("$.breed").value("Pug"))
                .andExpect(jsonPath("$.birthDate").value("2023-05-10"))
                .andExpect(jsonPath("$.plan").value("PREMIUM"))
                .andExpect(jsonPath("$.deductible").value(250))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-19T10:00:00Z"))
                .andExpect(jsonPath("$.validUntil").value("2026-12-19T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // raw check: jsonPath would read 35.00 as the double 35.0 and hide the scale
                .andExpect(content().string(containsString("\"monthlyPremium\":35.00")));

        verify(quoteService).create(EXPECTED_REQUEST);
    }

    @Test
    void createIgnoresUnknownFields() throws Exception {
        given(quoteService.create(EXPECTED_REQUEST)).willReturn(VIEW);
        String bodyWithExtraField = VALID_BODY.replace("\"country\": \"US\"", "\"country\": \"US\", \"color\": \"black\"");

        mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(bodyWithExtraField))
                .andExpect(status().isCreated());

        verify(quoteService).create(EXPECTED_REQUEST);
    }

    @Test
    void createWithoutBreedPassesNullBreedToService() throws Exception {
        QuoteRequest withoutBreed = new QuoteRequest(PetType.DOG, null, LocalDate.of(2023, 5, 10),
                Plan.PREMIUM, new BigDecimal("250"), "US");
        given(quoteService.create(withoutBreed)).willReturn(VIEW);
        String bodyWithoutBreed = VALID_BODY.replace("\"breed\": \"Pug\",", "");

        mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(bodyWithoutBreed))
                .andExpect(status().isCreated());

        verify(quoteService).create(withoutBreed);
    }

    @Test
    void getExistingQuoteReturns200WithBody() throws Exception {
        given(quoteService.get(ID)).willReturn(VIEW);

        mockMvc.perform(get("/quotes/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.plan").value("PREMIUM"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(content().string(containsString("\"monthlyPremium\":35.00")));
    }

    @Test
    void getUnknownQuoteReturns404ProblemDetail() throws Exception {
        UUID unknown = UUID.randomUUID();
        given(quoteService.get(unknown)).willThrow(new QuoteNotFoundException(unknown));

        mockMvc.perform(get("/quotes/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Quote not found"))
                .andExpect(jsonPath("$.detail").value(containsString(unknown.toString())));
    }

    @Test
    void businessRuleViolationReturns422ProblemDetail() throws Exception {
        given(quoteService.create(any())).willThrow(new BusinessRuleException("Country 'IL' is not supported"));

        mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Business rule violation"))
                .andExpect(jsonPath("$.detail").value("Country 'IL' is not supported"));
    }

    @Test
    void unexpectedErrorReturnsGeneric500WithoutInternals() throws Exception {
        given(quoteService.get(any())).willThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/quotes/{id}", ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}