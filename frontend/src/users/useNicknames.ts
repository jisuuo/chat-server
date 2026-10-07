import { createContext, createElement, useContext, useEffect, useMemo, useState } from 'react'
import type { Dispatch, PropsWithChildren, SetStateAction } from 'react'
import { listUsers } from '../api/chat'
import { defaultSenderName } from './senderName'

type NicknameState = {
  names: Map<number, string>
  setNames: Dispatch<SetStateAction<Map<number, string>>>
  requested: Set<number>
}

const NicknameContext = createContext<NicknameState | null>(null)

// ADR-119: 방을 옮겨도 같은 탭의 닉네임과 진행 중인 조회를 유지한다
export function NicknameProvider({ children }: PropsWithChildren) {
  const [names, setNames] = useState<Map<number, string>>(new Map())
  const requested = useMemo(() => new Set<number>(), [])
  return createElement(NicknameContext.Provider, { value: { names, setNames, requested } }, children)
}

export function useNicknames(userId: number, senderIds: number[]) {
  const shared = useContext(NicknameContext)
  const [localNames, setLocalNames] = useState<Map<number, string>>(new Map())
  const localRequested = useMemo(() => new Set<number>(), [])
  const names = shared?.names ?? localNames
  const setNames = shared?.setNames ?? setLocalNames
  const requested = shared?.requested ?? localRequested
  const missingKey = [...new Set(senderIds)].filter((id) => !names.has(id)).sort((a, b) => a - b).join(',')

  useEffect(() => {
    const ids = missingKey === '' ? [] : missingKey.split(',').map(Number).filter((id) => !requested.has(id))
    if (ids.length === 0) return
    ids.forEach((id) => requested.add(id))
    listUsers(userId, ids)
      .then(({ data }) => setNames((current) => {
        const next = new Map(current)
        for (const user of data) next.set(user.id, user.nickname)
        return next
      }))
      .catch(() => ids.forEach((id) => requested.delete(id)))
  }, [userId, missingKey, requested, setNames])

  return (senderId: number) => names.get(senderId) ?? defaultSenderName(senderId)
}
