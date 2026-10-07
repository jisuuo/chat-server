CREATE TABLE users (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    nickname   VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

-- last_message_id: 방 목록 정렬용 비정규화 컬럼 (ADR-016). 메시지가 없으면 NULL. FK 없음
CREATE TABLE rooms (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    name            VARCHAR(50) NOT NULL,
    created_by      BIGINT      NOT NULL,
    last_message_id BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

-- joined_message_id, joined_at: 입장 경계 실험(F18)용으로 둘 다 둔다
CREATE TABLE room_members (
    room_id           BIGINT      NOT NULL,
    user_id           BIGINT      NOT NULL,
    joined_message_id BIGINT      NOT NULL,
    joined_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_members_room FOREIGN KEY (room_id) REFERENCES rooms (id),
    CONSTRAINT fk_room_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 스키마 A: PK id
CREATE TABLE messages (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    room_id    BIGINT        NOT NULL,
    sender_id  BIGINT        NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_messages_room_id_id (room_id, id)
);

-- 스키마 B: PK (room_id, id). AUTO_INCREMENT 컬럼은 어떤 인덱스의 첫 컬럼이어야 해서 id 단독 인덱스를 둔다
CREATE TABLE messages_b (
    room_id    BIGINT        NOT NULL,
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    sender_id  BIGINT        NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (room_id, id),
    KEY idx_messages_b_id (id)
);
