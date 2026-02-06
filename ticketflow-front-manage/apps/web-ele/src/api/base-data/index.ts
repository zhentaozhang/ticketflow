import { requestClient } from '#/api/request';

export namespace BaseDataApi {
  /** 查询城市数据返回值 */
  export interface CityQueryResult {
    /** 区域id */
    id: string;
    /** 父区域id */
    parentId: string;
    /** 区域名字 */
    name: string[];
    /** 1:省 2:区 3:县 */
    type: string;
  }
}

/**
 * 用户后台系统显示的城市列表
 */
export async function areaManageListQueryApi() {
  return requestClient.post<BaseDataApi.CityQueryResult>('/basedata/area/manage/list');
}
