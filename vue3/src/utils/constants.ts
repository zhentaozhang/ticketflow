// 渠道编码
export const CHANNEL_CODE = '0001' as const

// 默认分页大小
export const DEFAULT_PAGE_SIZE = 10 as const

// 城市显示上限
export const CITY_DISPLAY_LIMIT = 22 as const

// 证件类型
export interface IdTypeItem {
  name: string
  value: string
}

export const ID_TYPE: IdTypeItem[] = [
  { name: '身份证', value: '1' },
  { name: '港澳台居民居住证', value: '2' },
  { name: '港澳台居民来往内地通行证', value: '3' },
  { name: '台湾居民来往内地通行证', value: '4' },
  { name: '护照', value: '5' },
  { name: '歪果仁永久居住证', value: '6' },
]
