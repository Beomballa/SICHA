package com.reasoning.common.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "story", schema = "public")
public class Story {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String code;
    @Column(name = "owner_id", nullable = false) public Long ownerId;
    @Column(name = "published_id") public Long publishedId;
    @Column(name = "active_yn", nullable = false) public boolean activeYn;
    @Column(name = "edit_rev", nullable = false) public long editRev;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    protected Story() {}
}
