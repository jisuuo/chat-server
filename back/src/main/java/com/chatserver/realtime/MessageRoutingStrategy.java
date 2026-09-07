package com.chatserver.realtime;

/**
 * Contract for routing a message to the clients subscribed to a room,
 * regardless of which server instance holds their WebSocket connection.
 * Implementations are added in a later phase (ADR-06).
 */
public interface MessageRoutingStrategy {

	void route(Long roomId, Long messageId);

}
