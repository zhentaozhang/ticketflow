import Cookies from 'js-cookie'

const TokenKey = 'Admin-Token'
const nameKey = 'userName'
const userIdKey = 'userId'

export function getToken(): string | undefined {
  return Cookies.get(TokenKey)
}

export function setToken(token: string): void {
  Cookies.set(TokenKey, token)
}

export function removeToken(): void {
  Cookies.remove(TokenKey)
}

// 设置 userName
export function getName(): string | undefined {
  return Cookies.get(nameKey)
}

export function setName(name: string): void {
  Cookies.set(nameKey, name)
}

export function removeName(): void {
  Cookies.remove(nameKey)
}

// 设置 userId
export function getUserIdKey(): string | undefined {
  return Cookies.get(userIdKey)
}

export function setUserIdKey(userId: string): void {
  Cookies.set(userIdKey, userId)
}

export function removeUserIdKey(): void {
  Cookies.remove(userIdKey)
}
