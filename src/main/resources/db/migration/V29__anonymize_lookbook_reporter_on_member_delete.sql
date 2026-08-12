-- 회원 탈퇴 후 신고 내역 유지 및 신고자 참조 익명 처리
ALTER TABLE lookbook_report
    DROP FOREIGN KEY FK_LOOKBOOK_REPORT_MEMBER,
    MODIFY COLUMN member_id BIGINT NULL,
    ADD CONSTRAINT FK_LOOKBOOK_REPORT_MEMBER_SET_NULL
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE SET NULL
        ON UPDATE RESTRICT;
