type Props = { userId: number; onOpen: (roomId: number) => void }

export function RoomListPage({ userId }: Props) {
  return <p>방 목록 (사용자 #{userId})</p>
}
