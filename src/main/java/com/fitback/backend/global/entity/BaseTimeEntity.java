package com.fitback.backend.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreateTimeEntity {

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
