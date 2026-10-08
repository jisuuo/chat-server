package jissuo.chat.user.infra.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nickname;
    private LocalDateTime createdAt;

    protected UserEntity() {
    }

    UserEntity(String nickname, LocalDateTime createdAt) {
        this.nickname = nickname;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getNickname() { return nickname; }
}
