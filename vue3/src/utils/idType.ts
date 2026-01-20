export type IdTypeCode = '1' | '2' | '3' | '4' | '5' | '6'

const idTypeMap: Record<string, string> = {
  '1': '身份证',
  '2': '港澳台居民居住证',
  '3': '港澳台居民来往内地通行证',
  '4': '台湾居民来往内地通行证',
  '5': '护照',
  '6': '歪果仁永久居住证',
}

export function getIdTypeName(idType: string | number): string {
  return idTypeMap[String(idType)] ?? '未知证件'
}
