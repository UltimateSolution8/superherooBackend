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
    }

    @Test
    public void testClassifier() {
        assertThrows(BadRequestException.class, () -> moderationService.validateTask("escort services or romance", "looking for hookup"));
    }
}
