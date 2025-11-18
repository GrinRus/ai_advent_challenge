package com.aiadvent.backend.profile.service;

public interface ProfileChangePublisher {
  void publish(ProfileChangedEvent event);
}
