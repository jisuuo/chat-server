// 컴포넌트 파일이 함수를 함께 내보내면 Fast Refresh가 동작하지 않아(react-refresh/only-export-components) 따로 둔다
export const defaultSenderName = (senderId: number) => `사용자 #${senderId}`
