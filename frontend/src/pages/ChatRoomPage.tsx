type Props = { userId: number; roomId: number; onBack: () => void }

export function ChatRoomPage({ roomId }: Props) {
  return <p>방 #{roomId}</p>
}
