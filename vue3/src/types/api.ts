export interface ApiResult<T = any> {
  code: number | string;
  data: T;
  message: string;
}
