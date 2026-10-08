package jissuo.chat.message.infra.jpa;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findByRoomIdAndIdGreaterThanOrderByIdDesc(long roomId, long boundaryId, Pageable page);
    List<MessageEntity> findByRoomIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(
            long roomId, long boundaryId, long beforeId, Pageable page);
    List<MessageEntity> findByRoomIdAndIdGreaterThanOrderByIdAsc(long roomId, long afterId, Pageable page);

    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanOrderByIdDesc(
            long roomId, LocalDateTime joinedAt, Pageable page);
    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanAndIdLessThanOrderByIdDesc(
            long roomId, LocalDateTime joinedAt, long beforeId, Pageable page);
    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanAndIdGreaterThanOrderByIdAsc(
            long roomId, LocalDateTime joinedAt, long afterId, Pageable page);
}
