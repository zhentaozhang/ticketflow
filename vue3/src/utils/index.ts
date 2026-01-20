import mitt, { Emitter } from 'mitt'

// 定义全局事件类型 Map
type Events = {
  searchList: unknown
  [key: string]: unknown
}

const emitter: Emitter<Events> = mitt<Events>()
export const useMitt = () => emitter

export function getCurrentDateTime(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = (now.getMonth() + 1).toString().padStart(2, '0')
  const day = now.getDate().toString().padStart(2, '0')
  const hours = now.getHours().toString().padStart(2, '0')
  const minutes = now.getMinutes().toString().padStart(2, '0')
  const seconds = now.getSeconds().toString().padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

export function getCurrentDate(dateStr: string): string {
  const now = new Date(dateStr)
  const year = now.getFullYear()
  const month = (now.getMonth() + 1).toString().padStart(2, '0')
  const day = now.getDate().toString().padStart(2, '0')
  return `${year}-${month}-${day}`
}

// 带周的格式化
export function formatDateWithWeekday(dateStr: string, week: string): string {
  const date = new Date(dateStr)
  const day = date.getDate()
  const month = date.getMonth() + 1
  const showMonth = month >= 1 && month <= 9 ? `0${month}` : `${month}`
  const year = date.getFullYear()
  const hours = date.getHours()
  const minutes = date.getMinutes()
  const showMinutes = minutes >= 0 && minutes <= 9 ? `0${minutes}` : `${minutes}`
  return `${year}.${showMonth}.${day}  ${week} ${hours}:${showMinutes}`
}

export function isPhoneNumber(value: string): boolean {
  return /^1(3|4|5|6|7|8|9)[0-9]\d{8}$/.test(value)
}

export function isEmailAddress(value: string): boolean {
  return /^\w+([-+.]\w+)*@\w+([-.]\\w+)*\.\w+([-.]\\w+)*$/.test(value)
}

export type OrderStatus = 1 | 2 | 3 | 4

export function getOrderStatus(orderStatus: OrderStatus | number): string {
  const statusMap: Record<number, string> = {
    1: '未支付',
    2: '交易关闭',
    3: '已支付',
    4: '交易关闭',
  }
  return statusMap[orderStatus] ?? '未知'
}
