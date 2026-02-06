/**
 * 前端分页函数（带数据克隆）
 * @param data 原始数据数组
 * @param currentPage 当前页码 (从1开始)
 * @param pageSize 每页显示数量
 * @returns 分页后的数据和分页信息
 */
export function paginate<T>(data: T[], currentPage: number, pageSize: number): {
  paginatedData: T[];
  total: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
} {
  // 深克隆原始数据
  const clonedData = deepClone(data);
  
  // 确保页码和页大小是有效的正整数
  const validPage = Math.max(1, Math.floor(currentPage));
  const validSize = Math.max(1, Math.floor(pageSize));
  
  // 计算总页数
  const total = clonedData.length;
  const totalPages = Math.ceil(total / validSize);
  
  // 计算当前页的实际页码（防止超出范围）
  const actualPage = Math.min(validPage, totalPages);
  
  // 计算分页起始和结束索引
  const startIndex = (actualPage - 1) * validSize;
  const endIndex = Math.min(startIndex + validSize, total);
  
  // 获取当前页数据
  const paginatedData = clonedData.slice(startIndex, endIndex);
  
  return {
    paginatedData,
    total,
    totalPages,
    currentPage: actualPage,
    pageSize: validSize,
  };
}

/**
 * 深克隆函数
 * @param obj 要克隆的对象或数组
 * @returns 深克隆后的新对象
 */
function deepClone<T>(obj: T): T {
  // 处理基本类型和null/undefined
  if (obj === null || typeof obj !== 'object') {
    return obj;
  }
  
  // 处理日期对象
  if (obj instanceof Date) {
    return new Date(obj.getTime()) as any;
  }
  
  // 处理数组
  if (Array.isArray(obj)) {
    return obj.map(item => deepClone(item)) as any;
  }
  
  // 处理普通对象
  const clonedObj: any = {};
  for (const key in obj) {
    if (obj.hasOwnProperty(key)) {
      clonedObj[key] = deepClone(obj[key]);
    }
  }
  
  return clonedObj;
}