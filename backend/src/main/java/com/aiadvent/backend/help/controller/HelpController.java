package com.aiadvent.backend.help.controller;

import com.aiadvent.backend.help.api.HelpResponse;
import com.aiadvent.backend.help.service.HelpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/help")
public class HelpController {

  private final HelpService helpService;

  public HelpController(HelpService helpService) {
    this.helpService = helpService;
  }

  @GetMapping
  public HelpResponse getHelp() {
    return new HelpResponse(helpService.getHelpMessage());
  }
}
