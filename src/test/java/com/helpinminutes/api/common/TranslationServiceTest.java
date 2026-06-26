package com.helpinminutes.api.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TranslationServiceTest {
    private TranslationService translationService;

    @BeforeEach
    public void setUp() {
        translationService = new TranslationService(new ObjectMapper());
    }

    @Test
    public void testEnglishTranslationBypasses() {
        String input = "Hello world";
        String output = translationService.translate(input, "en");
        assertEquals(input, output);
    }

    @Test
    public void testUnsupportedLanguageBypasses() {
        String input = "Hello world";
        String output = translationService.translate(input, "fr");
        assertEquals(input, output);
    }

    @Test
    public void testTranslationCachedOrFallbacks() {
        String input = "Water the plants";
        String output = translationService.translate(input, "te");
        assertNotNull(output);
    }
}
