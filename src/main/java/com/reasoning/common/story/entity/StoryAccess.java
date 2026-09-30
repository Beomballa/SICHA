package com.reasoning.common.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

@Entity
@Table(name = "story_access", schema = "public")
@IdClass(StoryAccess.Key.class)
public class StoryAccess {
    @Id
    @Column(name = "story_id")
    public Long storyId;

    @Id
    @Column(name = "admin_id")
    public Long adminId;

    @Id public String permission;

    @Column(name = "active_yn")
    public boolean activeYn;

    protected StoryAccess() {}

    public record Key(Long storyId, Long adminId, String permission) implements Serializable {}
}
