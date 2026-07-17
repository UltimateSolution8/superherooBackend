package com.helpinminutes.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.helpinminutes.api.errors.BadRequestException;
import org.junit.jupiter.api.Test;

class InputValidatorsTest {

  @Test
  void acceptsSyntacticallyValidCompanyEmails() {
    assertEquals("person@superherooo.com", InputValidators.requireEmail(" Person@Superherooo.com "));
    assertEquals("employee@facebook.com", InputValidators.requireEmail("employee@facebook.com"));
  }

  @Test
  void rejectsMalformedAndKnownTypoDomains() {
    assertThrows(BadRequestException.class, () -> InputValidators.requireEmail("ayooshgmail.com"));
    assertThrows(BadRequestException.class, () -> InputValidators.requireEmail("ayoosh@gzail.com"));
  }
}
