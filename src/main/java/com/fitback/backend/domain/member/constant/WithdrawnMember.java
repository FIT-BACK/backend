package com.fitback.backend.domain.member.constant;

//탈퇴 회원 계정(탈퇴한 유저) 예약 값 — 탈퇴 시 룩북 작성자를 이 계정으로 익명화
public final class WithdrawnMember {

    public static final String EMAIL = "withdrawn@fitback.internal";
    public static final String NICKNAME = "탈퇴한 유저";

    private WithdrawnMember() {}
}
