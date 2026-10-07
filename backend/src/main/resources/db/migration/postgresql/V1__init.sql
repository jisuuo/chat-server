CREATE TABLE users (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    nickname   VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

-- last_message_id: 방 목록 정렬용 비정규화 컬럼 (ADR-016). 메시지가 없으면 NULL. FK 없음
CREATE TABLE rooms (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    name            VARCHAR(50)  NOT NULL,
    created_by      BIGINT       NOT NULL,
    last_message_id BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

-- joined_message_id, joined_at: 입장 경계 실험(F18)용으로 둘 다 둔다
CREATE TABLE room_members (
    room_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    joined_message_id BIGINT       NOT NULL,
    joined_at         TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_members_room FOREIGN KEY (room_id) REFERENCES rooms (id),
    CONSTRAINT fk_room_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 스키마 A: PK id
CREATE TABLE messages (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    room_id    BIGINT        NOT NULL,
    sender_id  BIGINT        NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_messages_room_id_id ON messages (room_id, id);

-- 스키마 B: PK (room_id, id)
CREATE TABLE messages_b (
    room_id    BIGINT        NOT NULL,
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    sender_id  BIGINT        NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (room_id, id)
);
