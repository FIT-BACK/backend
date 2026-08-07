package com.fitback.backend.domain.tag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagTargetClothing;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DataJpaTest
class TagRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TagRepository tagRepository;

    @Test
    void findsOnlyMappedMasterTagsWithInitializedTargets() {
        Tag wideFit = entityManager.persist(Tag.create(
                "와이드핏",
                TagType.SILHOUETTE,
                List.of(TagTargetClothing.PANTS)
        ));
        entityManager.persist(Tag.create("legacy", TagType.DETAIL));
        entityManager.flush();
        entityManager.clear();

        List<Tag> result = tagRepository.findAllByOrderByIdAsc();
        entityManager.clear();

        assertThat(result).singleElement().satisfies(tag -> {
            assertThat(tag.getId()).isEqualTo(wideFit.getId());
            assertThat(tag.getTargetClothing()).containsExactly(TagTargetClothing.PANTS);
        });
    }
}
