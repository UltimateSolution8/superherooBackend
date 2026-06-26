package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.*;

import com.helpinminutes.api.errors.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TaskModerationServiceTest {
    private TaskModerationService moderationService;

    @BeforeEach
    public void setUp() {
        moderationService = new TaskModerationService();
        moderationService.init();
    }

    @Test
    public void testAllowedTasks() {
        assertDoesNotThrow(() -> moderationService.validateTask("Clean apartment kitchen", "Needs washing dishes and organizing cupboards"));
        assertDoesNotThrow(() -> moderationService.validateTask("Groceries delivery", "Buy milk, eggs, bread and bring to flat 302"));
    }

    @Test
    public void testBlacklistedKeywords() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Buy some weed", "Deliver to my address"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Need a doctor", "Write a medical prescription"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Buy whiskey", "Get a bottle of whiskey from liquor store"));
    }

    @Test
    public void testIllegalPattern() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Private erotic companion needed", "Must be adult companion"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Need a prostitute", "Bring her to home"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Looking for prostitution", "In Hyderabad"));
    }

    @Test
    public void testObfuscationBypasses() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Need s.e.x services", "obfuscated keyword"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Looking for s*e*x", "another obfuscation"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("s e x video", "spaced out keyword"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("download p*o*r*n movie", "porn obfuscation"));
    }

    @Test
    public void testNsfwMediaCombinations() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Download sex videos", "Normal looking request but has adult media"));
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("Send me nude photos", "Another adult media combination"));
    }

    @Test
    public void testExemptions() {
        assertDoesNotThrow(() -> moderationService.validateTask("Need low alcohol homeo medicine", "For personal healthcare use"));
        assertDoesNotThrow(() -> moderationService.validateTask("Pick up documents from lawyer office", "Delivery task"));
        assertDoesNotThrow(() -> moderationService.validateTask("Need a root beer", "Soft drink errand"));
        assertDoesNotThrow(() -> moderationService.validateTask("Sharpen kitchen knife", "Home maintenance request"));
        assertDoesNotThrow(() -> moderationService.validateTask("Buy a set of wine glasses", "Household item shopping"));
    }

    @Test
    public void testClassifier() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("escort services or romance", "looking for hookup"));
    }
}
