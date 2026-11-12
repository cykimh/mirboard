package com.mirboard.domain.sound;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SoundClipRepository extends JpaRepository<SoundClip, String> {

    /** 목록용 — 파일 바이트를 읽지 않는다. */
    @Query("select c.clipKey as clipKey, c.contentType as contentType, c.byteSize as byteSize, "
            + "c.updatedAt as updatedAt from SoundClip c order by c.clipKey")
    List<Summary> findSummaries();

    interface Summary {
        String getClipKey();

        String getContentType();

        int getByteSize();

        Instant getUpdatedAt();
    }
}
