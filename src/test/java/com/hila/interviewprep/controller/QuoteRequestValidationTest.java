package com.hila.interviewprep.controller;

import com.hila.interviewprep.config.JacksonConfig;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shape validation and strict JSON: every case here is a 400 and never reaches the service. */
@WebMvcTest(QuoteController.class)
@Import(JacksonConfig.class) // @WebMvcTest doesn't pick up @Configuration classes
class QuoteRequestValidationTest {

    /** 50 characters, the longest allowed breed. A literal so it can be used in annotations. */
    private static final String FIFTY = "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghij";

    private static final QuoteView VIEW = new QuoteView(
            new Quote(UUID.randomUUID(), PetType.DOG, "Pug", LocalDate.of(2023, 5, 10), Plan.PREMIUM,
                    new BigDecimal("250"), "US", new BigDecimal("49.14"), "USD",
                    Instant.parse("2026-11-19T10:00:00Z"), Instant.parse("2026-12-19T10:00:00Z")),
            QuoteStatus.ACTIVE);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    /** A valid body with one field replaced by a raw JSON value, or removed when {@code rawValue} is null. */
    private static String bodyWith(String field, String rawValue) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("petType", "\"DOG\"");
        fields.put("breed", "\"Pug\"");
        fields.put("birthDate", "\"2023-05-10\"");
        fields.put("plan", "\"PREMIUM\"");
        fields.put("deductible", "250");
        fields.put("country", "\"US\"");
        if (rawValue == null) {
            fields.remove(field);
        } else {
            fields.put(field, rawValue);
        }
        return fields.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post("/quotes").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 400 ProblemDetail with the standard fields and no internals, and the service was never called. */
    private ResultActions expectBadRequest(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        verifyNoInteractions(quoteService);
        return result;
    }

    // --- Malformed JSON (AC14) ---

    @ParameterizedTest
    @ValueSource(strings = {"{", "not json", "", "[]", "{\"petType\": }"})
    void malformedJsonIsRejected(String body) throws Exception {
        expectBadRequest(postBody(body));
    }

    // --- Missing or null required fields (AC15) ---

    @ParameterizedTest
    @ValueSource(strings = {"petType", "birthDate", "plan", "deductible", "country"})
    void missingRequiredFieldIsRejected(String field) throws Exception {
        expectBadRequest(postBody(bodyWith(field, null)))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"petType", "birthDate", "plan", "deductible", "country"})
    void nullRequiredFieldIsRejected(String field) throws Exception {
        expectBadRequest(postBody(bodyWith(field, "null")))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    // --- Wrong JSON types (AC16) ---

    static Stream<Arguments> wrongTypes() {
        return Stream.of(
                Arguments.of("deductible", "\"250\""),
                Arguments.of("deductible", "true"),
                Arguments.of("petType", "1"),
                Arguments.of("plan", "true"),
                Arguments.of("birthDate", "20230510"),
                Arguments.of("country", "1"),
                Arguments.of("breed", "123"),
                Arguments.of("petType", "{}"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("wrongTypes")
    void wrongJsonTypeIsRejectedAndNamesTheField(String field, String rawValue) throws Exception {
        expectBadRequest(postBody(bodyWith(field, rawValue)))
                .andExpect(jsonPath("$.detail").value("Invalid value for field '" + field + "'"));
    }

    // --- Unknown enum values, case-sensitive (AC17) ---

    static Stream<Arguments> unknownEnums() {
        return Stream.of(
                Arguments.of("petType", "\"BIRD\""),
                Arguments.of("petType", "\"dog\""),
                Arguments.of("plan", "\"GOLD\""),
                Arguments.of("plan", "\"premium\""));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("unknownEnums")
    void unknownEnumValueIsRejected(String field, String rawValue) throws Exception {
        expectBadRequest(postBody(bodyWith(field, rawValue)))
                .andExpect(jsonPath("$.detail").value("Invalid value for field '" + field + "'"));
    }

    // --- Bad dates (AC18) ---

    @ParameterizedTest
    @ValueSource(strings = {"\"2023-02-30\"", "\"2023-13-01\"", "\"10/05/2023\"", "\"2023-5-10\"", "\"\""})
    void badDateIsRejected(String rawDate) throws Exception {
        expectBadRequest(postBody(bodyWith("birthDate", rawDate)));
    }

    // --- Breed length after trimming (AC19) ---

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", FIFTY + "a"})
    void blankOrTooLongBreedIsRejected(String breed) throws Exception {
        expectBadRequest(postBody(bodyWith("breed", "\"" + breed + "\"")))
                .andExpect(jsonPath("$.errors[0].field").value("breed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {FIFTY, "  " + FIFTY + "  ", "Shih-Tzu", "Jack Russell"})
    void breedUpToFiftyCharactersAfterTrimmingIsAccepted(String breed) throws Exception {
        given(quoteService.create(any())).willReturn(VIEW);

        postBody(bodyWith("breed", "\"" + breed + "\"")).andExpect(status().isCreated());

        ArgumentCaptor<QuoteRequest> sent = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteService).create(sent.capture());
        assertThat(sent.getValue().breed()).isEqualTo(breed.strip());
    }

    // --- Several invalid fields are all reported (AC29) ---

    @Test
    void everyInvalidFieldIsListed() throws Exception {
        String body = bodyWith("plan", null).replace("\"Pug\"", "\"" + FIFTY + "a\"");

        expectBadRequest(postBody(body))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("breed"))
                .andExpect(jsonPath("$.errors[1].field").value("plan"));
    }

    // --- Path id (AC13) ---

    @Test
    void nonUuidIdIsRejected() throws Exception {
        expectBadRequest(mockMvc.perform(get("/quotes/{id}", "not-a-uuid")));
    }

    // --- Unknown fields stay ignored with the strict JSON config (AC31) ---

    @Test
    void unknownFieldIsStillIgnored() throws Exception {
        given(quoteService.create(any())).willReturn(VIEW);

        postBody(bodyWith("color", "\"black\"")).andExpect(status().isCreated());
    }

    // --- No internals in any error body (AC23) ---

    @Test
    void notFoundUnprocessableAndServerErrorsCarryNoInternals() throws Exception {
        UUID id = UUID.randomUUID();
        given(quoteService.get(id)).willThrow(new QuoteNotFoundException(id));
        given(quoteService.create(any())).willThrow(new BusinessRuleException("Country 'IL' is not supported"));
        UUID broken = UUID.randomUUID();
        given(quoteService.get(broken)).willThrow(new IllegalStateException("boom"));

        for (ResultActions result : new ResultActions[]{
                mockMvc.perform(get("/quotes/{id}", id)),
                postBody(bodyWith("country", "\"IL\"")),
                mockMvc.perform(get("/quotes/{id}", broken))}) {
            result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").exists())
                    .andExpect(jsonPath("$.title").exists())
                    .andExpect(jsonPath("$.detail").exists())
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist());
        }
    }
}
