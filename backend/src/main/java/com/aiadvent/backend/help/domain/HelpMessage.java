package com.aiadvent.backend.help.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "help_content")
public class HelpMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "message", nullable = false)
  private String message;

  protected HelpMessage() {}

  public HelpMessage(String message) {
    this.message = message;
  }

  public Long getId() {
    return id;
  }

  public String getMessage() {
    return message;
  }
}
