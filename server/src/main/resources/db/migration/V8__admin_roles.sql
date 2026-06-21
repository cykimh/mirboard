-- D-86 — 어드민 역할. 권한을 users 에 두지 않고 별도 테이블로 분리한다(규칙#3, D-02 화이트리스트 불변).
-- user_id 가 PK 이자 users(id) FK. 어드민 부여는 이 테이블 insert(운영 스크립트)로만 한다.
CREATE TABLE admin_roles (
    user_id    BIGINT     PRIMARY KEY,
    granted_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_roles_user FOREIGN KEY (user_id) REFERENCES users (id)
);
