package com.reasoning.common.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "story_version", schema = "public")
public class StoryVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "story_id", nullable = false) public Long storyId;
    @Column(name = "version_no", nullable = false) public int versionNo;
    @Column(name = "edit_rev", nullable = false) public long editRev;
    @Column(nullable = false) public String status;
    @Column(nullable = false) public String title;
    public String intro;
    public String setting;
    public Short difficulty;
    @Column(name = "est_min") public Short estMin;
    @Column(name = "est_max") public Short estMax;
    @Column(name = "limit_sec") public Integer limitSec;
    @Column(name = "policy_code", nullable = false) public String policyCode;
    @Column(name = "culprit_code") public String culpritCode;
    @Column(name = "method_answer") public String methodAnswer;
    @Column(name = "time_answer") public String timeAnswer;
    @Column(name = "motive_answer") public String motiveAnswer;
    @Column(name = "timeline_origin") public String timelineOrigin;
    @Column(name = "reveal_text") public String revealText;
    @Column(name = "current_snapshot_id") public Long currentSnapshotId;
    @Column(name = "active_yn", nullable = false) public boolean activeYn;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    protected StoryVersion() {}
}
