package com.chatserver.chatroom;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ChatRoom r where r.id = :id")
	Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

	@Query(value = """
			SELECT r.id AS id, r.name AS name, r.last_message_at AS lastMessageAt,
			       COALESCE(r.last_message_at, r.created_at) AS sortAt
			FROM chat_room_member m
			JOIN chat_room r ON r.id = m.chat_room_id
			WHERE m.user_id = :userId
			  AND m.left_at IS NULL
			ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
			LIMIT :limitPlusOne
			""", nativeQuery = true)
	List<ChatRoomSummaryProjection> findFirstPage(@Param("userId") String userId,
			@Param("limitPlusOne") int limitPlusOne);

	@Query(value = """
			SELECT r.id AS id, r.name AS name, r.last_message_at AS lastMessageAt,
			       COALESCE(r.last_message_at, r.created_at) AS sortAt
			FROM chat_room_member m
			JOIN chat_room r ON r.id = m.chat_room_id
			WHERE m.user_id = :userId
			  AND m.left_at IS NULL
			  AND (COALESCE(r.last_message_at, r.created_at), r.id) < (:cursorSortAt, :cursorRoomId)
			ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
			LIMIT :limitPlusOne
			""", nativeQuery = true)
	List<ChatRoomSummaryProjection> findNextPage(@Param("userId") String userId,
			@Param("cursorSortAt") OffsetDateTime cursorSortAt,
			@Param("cursorRoomId") Long cursorRoomId,
			@Param("limitPlusOne") int limitPlusOne);
}
