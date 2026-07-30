package com.fitback.backend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberImageReferenceProbeTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberImageReferenceProbe referenceProbe;

    @Test
    void returnsWhetherMemberProfileReferencesImage() {
        when(memberRepository.existsByProfileImageId("profile-image")).thenReturn(true);

        assertThat(referenceProbe.exists("profile-image")).isTrue();
    }
}
