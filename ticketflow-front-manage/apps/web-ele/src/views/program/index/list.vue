<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ProgramApi } from '#/api/program';
import { ref, onMounted, nextTick } from 'vue';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { ElMessage, ElMessageBox } from 'element-plus';
import { selectByTypeQueryApi, selectByParentProgramCategoryIdQueryApi, 
  programPageQueryApi, ticketCategoryListQueryApi, programAddApi, programInvalidApi } from '#/api/program';
import { recordPageQueryApi } from '#/api/order';
import { $t } from '#/locales';

import { useColumns, useSchema } from './data';
// 地区响应式数据存储下拉选项
const cityOptions = ref<Array<{ label: string; value: string }>>([]);
// 节目种类响应式数据存储下拉选项
const programCategoryOptions = ref<Array<{ label: string; value: string }>>([]);
const programChildCategoryOptions = ref<Array<{ label: string; value: string }>>([]);

// 加载二级分类的函数
async function loadChildOptions(parentId: string | undefined) {
  if (!parentId) {
    programChildCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
    return;
  }
  try {
    const list: any = await selectByParentProgramCategoryIdQueryApi({ "parentProgramCategoryId":parentId } as any);
    const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
    const mapped = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
    programChildCategoryOptions.value = [{ label: '全部', value: 'ALL' }, ...mapped];
  } catch (error) {
    programChildCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
  }
}


onMounted(async () => {
  // 等待下一个 tick，确保 gridApi 已经初始化
  await nextTick();
  
  // 并行加载所有下拉框数据
  const [cityResult, categoryResult] = await Promise.allSettled([
    // 加载城市数据
    areaManageListQueryApi(),
    // 加载节目分类数据
    selectByTypeQueryApi({ type: '1' } as any)
  ]);

  // 处理城市数据
  if (cityResult.status === 'fulfilled') {
    const list: any = cityResult.value;
    const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
    const mapped = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
    cityOptions.value = [{ label: '全部', value: 'ALL' }, ...mapped];
  } else {
    cityOptions.value = [{ label: '全部', value: 'ALL' }];
  }

  // 处理节目分类数据
  if (categoryResult.status === 'fulfilled') {
    const list: any = categoryResult.value;
    const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
    const mapped = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
    programCategoryOptions.value = [{ label: '全部', value: 'ALL' }, ...mapped];
  } else {
    programCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
  }

  // 初始化二级分类为全部
  programChildCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
});

const [Drawer, drawerApi] = useVbenDrawer();
// 余票详情抽屉
const [TicketDrawer, ticketDrawerApi] = useVbenDrawer();
const [Form] = useVbenForm({
  commonConfig: {
    // 所有表单项
    componentProps: {
      class: 'w-full',
    },
  },
  // 将表单整体设为两列栅格，使前两个项在第一行，时间项在第二行
  wrapperClass: 'grid-cols-2',
  resetButtonOptions: {
    content: '关闭',
  },
  layout: 'horizontal',
  handleSubmit: (values) => {
    onSubmit(values);
  },
  handleReset: () => {
    drawerApi.close();
  },
  schema: useSchema(),
});
// 表单组件，显示列表
const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    // 设置搜索表单为两列布局，使时间项换到下一行
    wrapperClass: 'grid-cols-2',
    commonConfig: {
      // 控件宽度限制，避免全宽过长
      controlClass: 'max-w-[260px] w-full',
      // 默认每项占1列
      formItemClass: 'col-span-1',
    },
    schema: [
      {
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          filterOption: true,
          options: cityOptions.value,
          showSearch: true,
        }),
        fieldName: 'areaId',
        label: '区域',
        // 独占第一行：Tailwind 栅格应使用 col-span-2
        formItemClass: 'col-span-2',
      },
      {
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          filterOption: true,
          options: programCategoryOptions.value,
          showSearch: true,
        }),
        fieldName: 'parentProgramCategoryId',
        label: '分类',
      },
      {
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          filterOption: true,
          options: programChildCategoryOptions.value,
          showSearch: true,
        }),
        fieldName: 'programCategoryId',
        label: '子类',
        dependencies: {
          triggerFields: ['parentProgramCategoryId'],
          trigger: async (values, actions) => {
            const parentId = (values as any)?.parentProgramCategoryId as string | undefined;
            // 切换父类时先清空当前值
            (actions as any)?.setFieldValue?.('programCategoryId', undefined);
            await loadChildOptions(parentId);
          },
        },
      },
    ],
    showCollapseButton: false,
    submitOnChange: false,
  },
  gridOptions: {
    columns: useColumns(onActionClick),
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: true, // 启用分页
      pageSize: 20, // 每页显示数量
      pageSizes: [10, 20, 50, 100], // 可选的每页显示数量
      currentPage: 1, // 当前页码
    },
    proxyConfig: {
      autoLoad: true, // 启用自动加载
      ajax: {
        query: async ({ page }, formValues) => {
          const areaId = (formValues as any)?.areaId as string | undefined;
          const parentProgramCategoryId = (formValues as any)?.parentProgramCategoryId as string | undefined;
          const programCategoryId = (formValues as any)?.programCategoryId as string | undefined;

          const res: any = await programPageQueryApi({
            areaId: areaId && areaId !== 'ALL' ? areaId : '',
            pageNumber: String(page.currentPage),
            pageSize: String(page.pageSize),
            parentProgramCategoryId: parentProgramCategoryId && parentProgramCategoryId !== 'ALL' ? parentProgramCategoryId : '',
            programCategoryId: programCategoryId && programCategoryId !== 'ALL' ? programCategoryId : '',
            timeType: "0",
            type: "1",
          });
          
          // 正确解析返回的数据结构
          const data = res?.data || res;
          const list = data?.list || [];
          const totalSize = data?.totalSize || 0;
          
          return {
            items: list,
            total: totalSize
          };
        },
      },
    },

    toolbarConfig: {
      custom: true, // 自定义工具栏
      export: false, // 禁用导出功能
      refresh: { code: 'query' }, // 启用刷新按钮，点击时执行查询
      search: true, // 启用搜索功能
      zoom: true, // 启用缩放功能
    },
  } as VxeTableGridOptions<ProgramApi.ProgramListResult>,
});

// 选中的节目ID（用于查询余票详情）
const selectedProgramId = ref<string>('');

// 余票详情表格
const [TicketGrid, ticketGridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: [
      { field: 'introduce', title: '介绍', minWidth: 160 },
      { field: 'price', title: '价格', minWidth: 80 },
      { field: 'totalNumber', title: '余票总数量', minWidth: 110 },
      { field: 'dbRemainNumber', title: '数据库中余票数量', minWidth: 150 },
      { field: 'redisRemainNumber', title: 'Redis中余票数量', minWidth: 140 },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async () => {
          if (!selectedProgramId.value) {
            return { items: [], total: 0 } as any;
          }
          const res: any = await ticketCategoryListQueryApi({ programId: selectedProgramId.value });
          const data = res?.data ?? res;
          let list: any[] = [];
          if (Array.isArray(data)) {
            list = data;
          } else if (Array.isArray(data?.list)) {
            list = data.list;
          } else if (Array.isArray(data?.items)) {
            list = data.items;
          }
          const mapped = list.map((item: any) => ({
            ...item,
            redisRemainNumber: item?.redisRemainNumber ? item.redisRemainNumber : '无',
          }));
          return {
            items: mapped,
            total: mapped.length,
          } as any;
        },
      },
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: false,
      zoom: false,
    },
  } as VxeTableGridOptions<ProgramApi.TicketCategoryListResult>,
});

// 记录详情：所选节目ID
const recordProgramId = ref<string>('');

// 记录详情抽屉与表格
const [RecordDrawer, recordDrawerApi] = useVbenDrawer();
const [RecordGrid, recordGridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: [
      { type: 'expand', width: 48, fixed: 'left', slots: { content: 'recordExpand' } },
      { field: 'orderNumber', title: '订单编号 (点击展开详情)', minWidth: 180, slots: { default: 'orderNumber' } },
      { field: 'reconciliationStatusName', title: '对账状态 (点击展开详情)', minWidth: 140, slots: { default: 'recordStatus' } },
    ],
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: true,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      currentPage: 1,
    },
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          if (!recordProgramId.value) {
            return { items: [], total: 0 } as any;
          }
          const res: any = await recordPageQueryApi({
            programId: recordProgramId.value,
            pageNumber: String(page.currentPage),
            pageSize: String(page.pageSize),
          } as any);
          const data = res?.data ?? res;
          const items = Array.isArray(data?.records)
            ? data.records
            : Array.isArray(data?.list)
            ? data.list
            : Array.isArray(data?.items)
            ? data.items
            : [];
          const total = Number(
            data?.total ?? data?.totalSize ?? (Array.isArray(items) ? items.length : 0),
          );
          return { items, total } as any;
        },
      },
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: false,
      zoom: false,
    },
  } as VxeTableGridOptions<any>,
});

function getStatusClass(status: string | number | undefined) {
  const code = String(status ?? '').trim();
  if (code === '1') return 'text-yellow-600';
  if (code === '-1') return 'text-red-600';
  if (code === '2' || code === '3') return 'text-green-600';
  return 'text-gray-600';
}

function expandRecordRow(row: any) {
  const grid = (recordGridApi as any)?.grid;
  if (!grid) return;
  if (typeof grid.toggleRowExpand === 'function') {
    grid.toggleRowExpand(row);
  } else if (typeof grid.setRowExpand === 'function') {
    grid.setRowExpand(row, true);
  }
}

function onActionClick(params: any) {
  if (params?.code === 'viewTicket') {
    selectedProgramId.value = String(params?.row?.id ?? '');
    ticketDrawerApi.open();
    nextTick(() => {
      (ticketGridApi as any)?.reload?.();
    });
  } else if (params?.code === 'viewRecord') {
    recordProgramId.value = String(params?.row?.id ?? '');
    recordDrawerApi.open();
    nextTick(() => {
      (recordGridApi as any)?.reload?.();
    });
  } else if (params?.code === 'invalidProgram') {
    const id = String(params?.row?.id ?? '');
    const title = params?.row?.title || '';
    ElMessageBox.confirm(`确定要下架节目【${title}】(ID: ${id}) 吗？`, '下架确认', {
      confirmButtonText: '确定下架',
      cancelButtonText: '取消',
      type: 'warning',
    }).then(async () => {
      try {
        const res: any = await programInvalidApi({ id });
        if (res?.code === '0' || res === true || res?.data === true) {
          ElMessage.success('下架成功');
          (gridApi as any)?.reload?.();
        } else {
          ElMessage.error(res?.message || '下架失败');
        }
      } catch (err: any) {
        ElMessage.error(err?.message || '下架异常');
      }
    }).catch(() => {});
  }
}

// 新增节目抽屉与表单
const [AddDrawer, addDrawerApi] = useVbenDrawer();
const addForm = ref({
  title: '',
  actor: '',
  place: '',
  itemPicture: '',
  areaId: '',
  parentProgramCategoryId: '',
  programCategoryId: '',
  showTime: '',
  notice: '',
  intro: '',
});

function openAddProgramDrawer() {
  addForm.value = {
    title: '',
    actor: '',
    place: '',
    itemPicture: '',
    areaId: cityOptions.value?.[0]?.value || '',
    parentProgramCategoryId: programCategoryOptions.value?.[0]?.value || '',
    programCategoryId: '',
    showTime: '',
    notice: '',
    intro: '',
  };
  addDrawerApi.open();
}

async function handleAddProgramSubmit() {
  if (!addForm.value.title || !addForm.value.areaId || !addForm.value.parentProgramCategoryId) {
    ElMessage.warning('请填写必填项：标题、区域与分类');
    return;
  }
  try {
    const res: any = await programAddApi({
      title: addForm.value.title,
      actor: addForm.value.actor,
      place: addForm.value.place,
      itemPicture: addForm.value.itemPicture,
      areaId: String(addForm.value.areaId),
      parentProgramCategoryId: String(addForm.value.parentProgramCategoryId),
      programCategoryId: String(addForm.value.programCategoryId || addForm.value.parentProgramCategoryId),
      showTime: addForm.value.showTime || '2026-07-26 19:30:00',
      notice: addForm.value.notice,
      intro: addForm.value.intro,
    });
    if (res?.code === '0' || res?.data) {
      ElMessage.success('新增节目成功！');
      addDrawerApi.close();
      (gridApi as any)?.reload?.();
    } else {
      ElMessage.error(res?.message || '新增节目失败');
    }
  } catch (err: any) {
    ElMessage.error(err?.message || '新增节目异常');
  }
}

function onSubmit(_values: any) {
  // 处理表单提交
}

function handleImageError(event: Event) {
  const img = event.target as HTMLImageElement;
  img.style.display = 'none';
  const parent = img.parentElement;
  if (parent) {
    parent.innerHTML = '<div class="w-16 h-12 bg-gray-200 rounded flex items-center justify-center text-xs text-gray-500">加载失败</div>';
  }
}

function previewImage(imageUrl: string, title: string) {
  // 创建图片预览弹窗
  const overlay = document.createElement('div');
  overlay.className = 'fixed inset-0 bg-black bg-opacity-75 flex items-center justify-center z-50 cursor-pointer';
  overlay.onclick = () => document.body.removeChild(overlay);
  
  const img = document.createElement('img');
  img.src = imageUrl;
  img.alt = title;
  img.className = 'max-w-[90vw] max-h-[90vh] object-contain rounded';
  
  overlay.appendChild(img);
  document.body.appendChild(overlay);
}
</script>
<template>
  <Page auto-content-height>
    <Drawer class="w-[600px]" title="编辑">
      <Form />
      <template #footer>
        <div></div>
      </template>
    </Drawer>
    <TicketDrawer class="w-[800px]" title="余票详情">
      <TicketGrid :table-title="'余票列表'" />
    </TicketDrawer>
    <RecordDrawer class="w-[100vw] max-w-[1400px]" title="记录详情">
      <RecordGrid :table-title="'记录列表'">
        <template #orderNumber="{ row }">
          <div
            class="cursor-pointer opacity-80 hover:opacity-100 transition-colors transition-opacity duration-300 ease-in-out hover:bg-blue-100 hover:ring-1 hover:ring-blue-300 hover:border-l-2 hover:border-blue-500 focus:ring-2 focus:ring-blue-300 p-1 pl-2 rounded hover:shadow-sm"
            @click="expandRecordRow(row)"
            title="点击展开"
          >
            <span class="mr-2">{{ row.orderNumber }}</span>
          </div>
        </template>
        <template #recordStatus="{ row }">
          <div
            class="cursor-pointer opacity-80 hover:opacity-100 transition-colors transition-opacity duration-300 ease-in-out hover:bg-blue-100 hover:ring-1 hover:ring-blue-300 hover:border-l-2 hover:border-blue-500 focus:ring-2 focus:ring-blue-300 p-1 pl-2 rounded hover:shadow-sm"
            @click="expandRecordRow(row)"
            title="点击展开"
          >
            <span :class="getStatusClass(row.reconciliationStatus)">{{ row.reconciliationStatusName }}</span>
          </div>
        </template>
        <template #recordExpand="{ row }">
          <div class="p-2 bg-gray-50 rounded">
            <table class="table-fixed w-full text-left text-sm">
              <thead>
                <tr>
                  <th class="py-1 px-2 w-[12%]">购票人订单id</th>
                  <th class="py-1 px-2 w-[12%]">购票人id</th>
                  <th class="py-1 px-2 w-[5%]">座位信息</th>
                  <th class="py-1 px-2 w-[9%]">redis座位之前状态</th>
                  <th class="py-1 px-2 w-[9%]">redis座位之后状态</th>
                  <th class="py-1 px-2 w-[8%]">票档信息</th>
                  <th class="py-1 px-2 w-[6%]">订单价格</th>
                  <th class="py-1 px-2 w-[6%]">数据库操作</th>
                  <th class="py-1 px-2 w-[6%]">Redis操作</th>
                  <th class="py-1 px-2 w-[5%]">对账状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(d, idx) in (row.recordOrderTickerUserManageVoList || [])" :key="idx">
                  <td class="py-1 px-2 truncate">{{ d.ticketUserOrderId }}</td>
                  <td class="py-1 px-2 truncate">{{ d.ticketUserId }}</td>
                  <td class="py-1 px-2 truncate">{{ d.seatInfo }}</td>
                  <td class="py-1 px-2">{{ d.redisBeforeSeatStatusName }}</td>
                  <td class="py-1 px-2">{{ d.redisAfterSeatStatusName }}</td>
                  <td class="py-1 px-2">{{ d.ticketCategoryName }}</td>
                  <td class="py-1 px-2">{{ d.orderPrice }}</td>
                  <td class="py-1 px-2">{{ d.dbRecordTypeName }}</td>
                  <td class="py-1 px-2">{{ d.redisRecordTypeName }}</td>
                  <td class="py-1 px-2">
                    <span :class="getStatusClass(d.reconciliationStatus)">{{ d.reconciliationStatusName }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </RecordGrid>
    </RecordDrawer>
    <AddDrawer class="w-[600px]" title="新增节目">
      <div class="p-4 space-y-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1"><span class="text-red-500">*</span> 节目标题</label>
          <input v-model="addForm.title" type="text" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="请输入节目标题" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1"><span class="text-red-500">*</span> 演出地区</label>
            <select v-model="addForm.areaId" class="w-full border rounded px-3 py-1.5 text-sm h-9">
              <option v-for="opt in cityOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1"><span class="text-red-500">*</span> 节目分类</label>
            <select v-model="addForm.parentProgramCategoryId" class="w-full border rounded px-3 py-1.5 text-sm h-9">
              <option v-for="opt in programCategoryOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">演出明星/艺人</label>
            <input v-model="addForm.actor" type="text" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="如：伍佰" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">演出场馆/地点</label>
            <input v-model="addForm.place" type="text" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="如：WeShow Live" />
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">简介图片URL</label>
          <input v-model="addForm.itemPicture" type="text" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="https://..." />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">演出时间</label>
          <input v-model="addForm.showTime" type="text" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="2026-07-26 19:30:00" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">观演须知</label>
          <textarea v-model="addForm.notice" rows="2" class="w-full border rounded px-3 py-1.5 text-sm" placeholder="观演注意事项..."></textarea>
        </div>
        <div class="pt-4 flex justify-end gap-2 border-t">
          <button class="px-4 py-1.5 border rounded text-sm text-gray-600 hover:bg-gray-50" @click="addDrawerApi.close()">取消</button>
          <button class="px-4 py-1.5 bg-blue-600 text-white rounded text-sm hover:bg-blue-700 font-medium" @click="handleAddProgramSubmit">确认提交</button>
        </div>
      </div>
    </AddDrawer>
    <Grid :table-title="$t('ticketflow.index.programListTitle')">
      <template #toolbar-tools>
        <button
          class="bg-blue-600 hover:bg-blue-700 text-white px-3 py-1.5 rounded text-sm font-medium flex items-center gap-1 shadow-sm mr-2"
          @click="openAddProgramDrawer"
        >
          + 新增节目
        </button>
      </template>
      <template #itemPicture="{ row }">
        <div class="w-16 h-12 flex items-center justify-center">
          <img
            v-if="row.itemPicture"
            :src="row.itemPicture"
            :alt="row.title"
            class="w-16 h-12 object-cover rounded cursor-pointer hover:opacity-80 transition-opacity"
            @error="handleImageError"
            @click="previewImage(row.itemPicture, row.title)"
          />
          <div v-else class="w-16 h-12 bg-gray-200 rounded flex items-center justify-center text-xs text-gray-500">
            暂无图片
          </div>
        </div>
      </template>
    </Grid>
  </Page>
</template>
